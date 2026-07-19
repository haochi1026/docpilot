from __future__ import annotations

import os
import sqlite3
import json
import logging
from datetime import datetime, timedelta, timezone
from contextlib import contextmanager
from typing import Any, Iterator

from langgraph.checkpoint.sqlite import SqliteSaver

from .settings import Settings

log = logging.getLogger(__name__)


class CheckpointProvider:
    """Select a local SQLite saver or a production PostgreSQL saver per run."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self._postgres_initialized = False

    @contextmanager
    def open(self) -> Iterator[Any]:
        if self.settings.checkpoint_backend == "postgres":
            from langgraph.checkpoint.postgres import PostgresSaver

            with PostgresSaver.from_conn_string(
                self.settings.checkpoint_dsn
            ) as checkpointer:
                if not self._postgres_initialized:
                    checkpointer.setup()
                    self._postgres_initialized = True
                yield checkpointer
            return

        parent = os.path.dirname(self.settings.checkpoint_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        connection = sqlite3.connect(
            self.settings.checkpoint_path,
            timeout=30,
            check_same_thread=False,
        )
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA busy_timeout=30000")
        try:
            yield SqliteSaver(connection)
        finally:
            connection.close()

    def cleanup(self, retention_days: int) -> int:
        """Delete old local checkpoints; PostgreSQL retention is handled by SQL below."""
        cutoff = (datetime.now(timezone.utc) - timedelta(days=retention_days)).isoformat()
        if self.settings.checkpoint_backend == "sqlite":
            connection = sqlite3.connect(self.settings.checkpoint_path, timeout=30, check_same_thread=False)
            try:
                deleted = 0
                columns = {row[1] for row in connection.execute("PRAGMA table_info(checkpoints)")}
                if {"thread_id", "checkpoint_ns", "checkpoint_id"}.issubset(columns):
                    timestamp_column = "metadata" if "metadata" in columns else "checkpoint"
                    rows = connection.execute(
                        f"SELECT thread_id,checkpoint_ns,checkpoint_id,{timestamp_column} FROM checkpoints"
                    ).fetchall()
                    by_thread: dict[tuple[str, str], list[tuple[str, str]]] = {}
                    decoded: list[tuple[str, str, str, str]] = []
                    for thread_id, checkpoint_ns, checkpoint_id, metadata in rows:
                        try:
                            value = json.loads(metadata or "{}")
                            created = str(value.get("created_at", value.get("ts", "")))
                            if not created and isinstance(value.get("checkpoint"), dict):
                                created = str(value["checkpoint"].get("ts", ""))
                        except (TypeError, ValueError, json.JSONDecodeError):
                            created = ""
                        decoded.append((thread_id, checkpoint_ns, checkpoint_id, created))
                        by_thread.setdefault((thread_id, checkpoint_ns), []).append((checkpoint_id, created))
                    for thread_id, checkpoint_ns, checkpoint_id, created in decoded:
                        newer_exists = any(
                            other_id != checkpoint_id and other_created and other_created > created
                            for other_id, other_created in by_thread.get((thread_id, checkpoint_ns), [])
                        )
                        # Retain the newest checkpoint for a thread even when
                        # the entire thread is older than the retention window.
                        if created and created < cutoff and newer_exists:
                            connection.execute(
                                "DELETE FROM checkpoint_writes WHERE thread_id=? AND checkpoint_ns=? AND checkpoint_id=?",
                                (thread_id, checkpoint_ns, checkpoint_id),
                            )
                            connection.execute(
                                "DELETE FROM checkpoint_blobs WHERE thread_id=? AND checkpoint_ns=? AND checkpoint_id=?",
                                (thread_id, checkpoint_ns, checkpoint_id),
                            )
                            deleted += connection.execute(
                                "DELETE FROM checkpoints WHERE thread_id=? AND checkpoint_ns=? AND checkpoint_id=?",
                                (thread_id, checkpoint_ns, checkpoint_id),
                            ).rowcount
                connection.commit()
                return max(0, deleted)
            finally:
                connection.close()
        # LangGraph's PostgresSaver stores the timestamp in checkpoint JSON. Keep
        # the newest checkpoint for every thread and delete its dependent writes and
        # blobs in one transaction. A missing/older LangGraph schema is reported,
        # rather than silently claiming cleanup succeeded.
        try:
            import psycopg

            with psycopg.connect(self.settings.checkpoint_dsn) as connection:
                with connection.cursor() as cursor:
                    cursor.execute(
                        """
                        WITH stale AS (
                          SELECT c.thread_id,c.checkpoint_ns,c.checkpoint_id
                          FROM checkpoints c
                          WHERE (c.checkpoint->>'ts')::timestamptz < %s
                            AND EXISTS (
                              SELECT 1 FROM checkpoints newer
                              WHERE newer.thread_id=c.thread_id
                                AND newer.checkpoint_ns=c.checkpoint_ns
                                AND (newer.checkpoint->>'ts')::timestamptz > (c.checkpoint->>'ts')::timestamptz
                            )
                        )
                        DELETE FROM checkpoint_writes w
                        USING stale s
                        WHERE w.thread_id=s.thread_id
                          AND w.checkpoint_ns=s.checkpoint_ns
                          AND w.checkpoint_id=s.checkpoint_id
                        """,
                        (cutoff,),
                    )
                    cursor.execute(
                        """
                        WITH stale AS (
                          SELECT c.thread_id,c.checkpoint_ns,c.checkpoint_id
                          FROM checkpoints c
                          WHERE (c.checkpoint->>'ts')::timestamptz < %s
                            AND EXISTS (
                              SELECT 1 FROM checkpoints newer
                              WHERE newer.thread_id=c.thread_id
                                AND newer.checkpoint_ns=c.checkpoint_ns
                                AND (newer.checkpoint->>'ts')::timestamptz > (c.checkpoint->>'ts')::timestamptz
                            )
                        )
                        DELETE FROM checkpoint_blobs b
                        USING stale s
                        WHERE b.thread_id=s.thread_id
                          AND b.checkpoint_ns=s.checkpoint_ns
                          AND b.checkpoint_id=s.checkpoint_id
                        """,
                        (cutoff,),
                    )
                    cursor.execute(
                        """
                        DELETE FROM checkpoints c
                        WHERE (c.checkpoint->>'ts')::timestamptz < %s
                          AND EXISTS (
                            SELECT 1 FROM checkpoints newer
                            WHERE newer.thread_id=c.thread_id
                              AND newer.checkpoint_ns=c.checkpoint_ns
                              AND (newer.checkpoint->>'ts')::timestamptz > (c.checkpoint->>'ts')::timestamptz
                          )
                        """,
                        (cutoff,),
                    )
                    return max(0, cursor.rowcount)
        except Exception as exc:
            log.warning("PostgreSQL checkpoint cleanup failed: %s", exc)
            return 0
