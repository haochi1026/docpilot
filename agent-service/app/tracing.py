from __future__ import annotations

import contextvars
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

import httpx

from .settings import Settings


@dataclass
class TraceContext:
    trace_id: str
    request_id: str
    thread_id: str
    started_at: float = field(default_factory=time.perf_counter)


_current_trace: contextvars.ContextVar[TraceContext | None] = contextvars.ContextVar(
    "docpilot_trace", default=None
)


class TraceClient:
    """Best-effort trace export. Telemetry failures never break an Agent run."""

    def __init__(self, settings: Settings, client: httpx.Client | None = None) -> None:
        self.enabled = settings.agentops_enabled
        self.base_url = settings.agentops_base_url.rstrip("/")
        self.api_key = settings.agentops_api_key
        self.client = client or httpx.Client(timeout=settings.agentops_timeout_seconds)

    def close(self) -> None:
        self.client.close()

    def begin(
        self,
        *,
        request_id: str | None,
        thread_id: str,
        input_preview: str,
        metadata: dict[str, Any],
    ) -> tuple[TraceContext, contextvars.Token[TraceContext | None]]:
        context = TraceContext(
            trace_id=str(uuid.uuid4()),
            request_id=request_id or str(uuid.uuid4()),
            thread_id=thread_id,
        )
        token = _current_trace.set(context)
        self._post(
            "/api/v1/traces",
            {
                "id": context.trace_id,
                "service_name": "docpilot-agent",
                "thread_id": thread_id,
                "request_id": context.request_id,
                "status": "RUNNING",
                "input_preview": _preview(input_preview),
                "metadata": metadata,
            },
        )
        return context, token

    def span(
        self,
        *,
        name: str,
        kind: str,
        status: str,
        duration_ms: int,
        input_data: Any = None,
        output_data: Any = None,
        error: str | None = None,
    ) -> None:
        context = _current_trace.get()
        if context is None:
            return
        self._post(
            f"/api/v1/traces/{context.trace_id}/spans",
            {
                "name": name,
                "kind": kind,
                "status": status,
                "duration_ms": max(0, duration_ms),
                "input": _redact(input_data),
                "output": _redact(output_data),
                "error": _preview(error or "") or None,
            },
        )

    def finish(
        self,
        context: TraceContext,
        token: contextvars.Token[TraceContext | None],
        *,
        status: str,
        output_preview: str = "",
        error: str | None = None,
    ) -> None:
        duration_ms = int((time.perf_counter() - context.started_at) * 1000)
        self._patch(
            f"/api/v1/traces/{context.trace_id}",
            {
                "status": status,
                "duration_ms": duration_ms,
                "output_preview": _preview(output_preview),
                "error": _preview(error or "") or None,
            },
        )
        _current_trace.reset(token)

    def _headers(self) -> dict[str, str]:
        return {"X-Platform-Key": self.api_key} if self.api_key else {}

    def _post(self, path: str, payload: dict[str, Any]) -> None:
        if not self.enabled:
            return
        try:
            self.client.post(self.base_url + path, headers=self._headers(), json=payload)
        except httpx.HTTPError:
            return

    def _patch(self, path: str, payload: dict[str, Any]) -> None:
        if not self.enabled:
            return
        try:
            self.client.patch(self.base_url + path, headers=self._headers(), json=payload)
        except httpx.HTTPError:
            return


def current_trace() -> TraceContext | None:
    return _current_trace.get()


def _preview(value: str, limit: int = 500) -> str:
    value = value.strip()
    return value if len(value) <= limit else value[:limit] + "..."


def _redact(value: Any) -> Any:
    if isinstance(value, dict):
        result: dict[str, Any] = {}
        for key, item in value.items():
            lowered = str(key).lower()
            if any(marker in lowered for marker in ("key", "token", "secret", "password")):
                result[str(key)] = "***"
            else:
                result[str(key)] = _redact(item)
        return result
    if isinstance(value, list):
        return [_redact(item) for item in value[:20]]
    if isinstance(value, str):
        return _preview(value)
    return value
