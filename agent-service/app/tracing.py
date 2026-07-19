from __future__ import annotations

import contextvars
import base64
import hashlib
import hmac
import json
import os
import time
import uuid
from dataclasses import dataclass, field
from typing import Any

import httpx

try:
    from opentelemetry import trace as otel_trace
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor
    from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import OTLPSpanExporter
except ImportError:
    otel_trace = None

from .settings import Settings
from .metrics import metrics


@dataclass
class TraceContext:
    trace_id: str
    request_id: str
    thread_id: str
    started_at: float = field(default_factory=time.perf_counter)
    exported: bool = False
    metadata: dict[str, Any] = field(default_factory=dict)


_current_trace: contextvars.ContextVar[TraceContext | None] = contextvars.ContextVar(
    "docpilot_trace", default=None
)


class TraceClient:
    """Best-effort trace export. Telemetry failures never break an Agent run."""

    def __init__(self, settings: Settings, client: httpx.Client | None = None) -> None:
        self.enabled = settings.agentops_enabled
        self.base_url = settings.agentops_base_url.rstrip("/")
        self.api_key = settings.agentops_api_key
        self.identity_token = settings.agentops_identity_token
        self.identity_token_secret = settings.agentops_identity_token_secret
        self.identity_subject = settings.agentops_identity_subject
        self.identity_tenant = settings.agentops_identity_tenant
        self.identity_role = settings.agentops_identity_role
        self.client = client or httpx.Client(timeout=settings.agentops_timeout_seconds)
        self._otel = None
        if otel_trace is not None:
            provider = TracerProvider(resource=Resource.create({"service.name": os.getenv("OTEL_SERVICE_NAME", "docpilot-agent")}))
            endpoint = os.getenv("OTEL_EXPORTER_OTLP_ENDPOINT", "").strip()
            if endpoint:
                provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint, insecure=True)))
            otel_trace.set_tracer_provider(provider)
            self._otel = otel_trace.get_tracer("docpilot-agent")

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
            metadata=dict(metadata),
        )
        token = _current_trace.set(context)
        context.exported = self._post(
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
        if self._otel is not None:
            with self._otel.start_as_current_span(
                name,
                attributes={
                    "agent.span.kind": kind,
                    "agent.status": status,
                    "llm.model": context.metadata.get("model", "unknown"),
                    "llm.prompt_version": context.metadata.get("prompt_version", "unknown"),
                    "llm.input_tokens": max(1, len(str(input_data or "")) // 4),
                    "llm.output_tokens": max(0, len(str(output_data or "")) // 4),
                    "llm.cost_usd": float(context.metadata.get("cost_usd", 0.0) or 0.0),
                    "agent.duration_ms": max(0, duration_ms),
                },
            ) as otel_span:
                otel_span.set_attribute("agent.duration_ms", max(0, duration_ms))
        if not context.exported:
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
        if context.exported:
            self._patch(
                f"/api/v1/traces/{context.trace_id}",
                {
                    "status": status,
                    "duration_ms": duration_ms,
                    "output_preview": _preview(output_preview),
                    "error": _preview(error or "") or None,
                },
            )
        # Starlette may advance a synchronous streaming iterator in copied worker
        # contexts. A token created by ``set`` cannot be reset from a different
        # Context, while assigning the default is safe and still prevents trace
        # state from leaking into a later request in the current context.
        _current_trace.set(None)

    def authorize_tool(self, tool_name: str, method: str, approved: bool, tenant_id: str = "default") -> bool:
        if not self.enabled:
            return True
        try:
            response = self.client.post(
                self.base_url + "/api/v1/tool-policies/check",
                headers=self._headers(),
                json={"tool_name": tool_name, "method": method, "approved": approved, "tenant_id": tenant_id},
            )
            response.raise_for_status()
            return bool(response.json().get("allowed"))
        except (httpx.HTTPError, ValueError):
            return False

    def _headers(self) -> dict[str, str]:
        headers: dict[str, str] = {}
        if self.api_key:
            headers["X-Platform-Key"] = self.api_key
        identity = self.identity_token or self._signed_identity_token()
        if identity:
            headers["X-Identity-Token"] = identity
        return headers

    def _signed_identity_token(self) -> str:
        """Mint a short-lived service identity instead of storing an expiring token."""
        if not self.identity_token_secret:
            return ""
        raw = json.dumps(
            {
                "sub": self.identity_subject,
                "tenant": self.identity_tenant,
                "role": self.identity_role,
                "exp": int(time.time()) + 300,
            },
            separators=(",", ":"),
        ).encode()
        encoded = base64.urlsafe_b64encode(raw).decode().rstrip("=")
        signature = base64.urlsafe_b64encode(
            hmac.new(
                self.identity_token_secret.encode(),
                encoded.encode(),
                hashlib.sha256,
            ).digest()
        ).decode().rstrip("=")
        return f"{encoded}.{signature}"

    def _post(self, path: str, payload: dict[str, Any]) -> bool:
        if not self.enabled:
            return False
        return self._send("POST", path, payload)

    def _patch(self, path: str, payload: dict[str, Any]) -> bool:
        if not self.enabled:
            return False
        return self._send("PATCH", path, payload)

    def _send(self, method: str, path: str, payload: dict[str, Any]) -> bool:
        for attempt in range(3):
            try:
                response = self.client.request(
                    method, self.base_url + path, headers=self._headers(), json=payload
                )
                response.raise_for_status()
                metrics.inc(
                    "docpilot_agent_trace_exports_total", status="success", method=method
                )
                return True
            except httpx.HTTPError:
                if attempt < 2:
                    time.sleep(0.05 * (2**attempt))
        metrics.inc(
            "docpilot_agent_trace_exports_total", status="error", method=method
        )
        return False


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
