from __future__ import annotations

import os
import sqlite3
from contextlib import contextmanager
from typing import Any, Iterator

from langgraph.checkpoint.sqlite import SqliteSaver

from .settings import Settings


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
