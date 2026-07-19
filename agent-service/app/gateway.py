from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from typing import Any

import httpx

from .metrics import metrics
from .settings import Settings
from .tracing import TraceClient


class GatewayError(RuntimeError):
    pass


@dataclass
class _Circuit:
    failures: int = 0
    opened_at: float | None = None


class InternalGateway:
    """Call deterministic Java services with retries, circuit breaking and tracing."""

    def __init__(
        self,
        settings: Settings,
        client: httpx.Client | None = None,
        trace_client: TraceClient | None = None,
    ) -> None:
        self.settings = settings
        self.client = client or httpx.Client(timeout=settings.request_timeout_seconds)
        self.trace_client = trace_client
        self._circuits: dict[str, _Circuit] = {}
        self._lock = threading.Lock()

    def close(self) -> None:
        self.client.close()

    def _request(
        self,
        method: str,
        base_url: str,
        path: str,
        key: str,
        username: str,
        *,
        tool_name: str,
        params: dict[str, Any] | None = None,
        json: dict[str, Any] | None = None,
        approval_id: str | None = None,
        approval_token: str | None = None,
        retryable: bool = True,
    ) -> Any:
        if self.settings.agentops_governance_enabled and self.trace_client:
            approved = bool(approval_id and approval_token)
            if not self.trace_client.authorize_tool(tool_name, method, approved):
                raise GatewayError("AgentOps tool policy denied this call")
        circuit_key = base_url.rstrip("/")
        self._check_circuit(circuit_key)
        headers = {"X-Agent-Key": key, "X-Username": username}
        if approval_id:
            headers["X-Agent-Approval-Id"] = approval_id
        if approval_token:
            headers["X-Agent-Approval-Token"] = approval_token

        attempts = self.settings.gateway_max_retries + 1 if retryable else 1
        started = time.perf_counter()
        last_error: Exception | None = None
        for attempt in range(attempts):
            try:
                response = self.client.request(
                    method,
                    circuit_key + path,
                    headers=headers,
                    params=params,
                    json=json,
                )
                response.raise_for_status()
                self._record_success(circuit_key)
                result = None if response.status_code == 204 or not response.content else response.json()
                duration_ms = int((time.perf_counter() - started) * 1000)
                metrics.inc(
                    "docpilot_agent_gateway_requests_total",
                    tool=tool_name,
                    status="success",
                )
                if self.trace_client:
                    self.trace_client.span(
                        name=tool_name,
                        kind="TOOL",
                        status="SUCCESS",
                        duration_ms=duration_ms,
                        input_data={"params": params, "body": json},
                        output_data=result,
                    )
                return result
            except httpx.HTTPStatusError as exc:
                last_error = exc
                # Client errors are deterministic; retrying them only adds latency.
                if exc.response.status_code < 500:
                    break
            except httpx.HTTPError as exc:
                last_error = exc
            if attempt + 1 < attempts:
                time.sleep(self.settings.gateway_backoff_seconds * (2**attempt))

        self._record_failure(circuit_key)
        duration_ms = int((time.perf_counter() - started) * 1000)
        message = self._message(last_error)
        metrics.inc(
            "docpilot_agent_gateway_requests_total",
            tool=tool_name,
            status="error",
        )
        if self.trace_client:
            self.trace_client.span(
                name=tool_name,
                kind="TOOL",
                status="ERROR",
                duration_ms=duration_ms,
                input_data={"params": params, "body": json},
                error=message,
            )
        raise GatewayError(message) from last_error

    def _message(self, error: Exception | None) -> str:
        if isinstance(error, httpx.HTTPStatusError):
            try:
                detail = error.response.json().get("message")
            except Exception:
                detail = error.response.text
            return detail or f"tool gateway returned {error.response.status_code}"
        if isinstance(error, httpx.HTTPError):
            return f"tool gateway unavailable: {error}"
        return "tool gateway failed"

    def _check_circuit(self, key: str) -> None:
        with self._lock:
            circuit = self._circuits.setdefault(key, _Circuit())
            if circuit.opened_at is None:
                return
            elapsed = time.monotonic() - circuit.opened_at
            if elapsed >= self.settings.circuit_recovery_seconds:
                circuit.opened_at = None
                circuit.failures = 0
                return
        raise GatewayError("tool gateway circuit is open; retry later")

    def _record_success(self, key: str) -> None:
        with self._lock:
            self._circuits[key] = _Circuit()

    def _record_failure(self, key: str) -> None:
        with self._lock:
            circuit = self._circuits.setdefault(key, _Circuit())
            circuit.failures += 1
            if circuit.failures >= self.settings.circuit_failure_threshold:
                circuit.opened_at = time.monotonic()

    def list_knowledge_bases(self, username: str) -> list[dict[str, Any]]:
        return self._request(
            "GET",
            self.settings.docpilot_base_url,
            "/api/internal/agent/knowledge-bases",
            self.settings.docpilot_internal_key,
            username,
            tool_name="list_accessible_knowledge_bases",
        )

    def search_knowledge_base(
        self, username: str, kb_id: int, query: str, top_k: int
    ) -> list[dict[str, Any]]:
        return self._request(
            "POST",
            self.settings.docpilot_base_url,
            "/api/internal/agent/search",
            self.settings.docpilot_internal_key,
            username,
            tool_name="search_knowledge_base",
            json={"kbId": kb_id, "query": query, "topK": top_k},
        )

    def get_chunk(self, username: str, chunk_id: int) -> dict[str, Any]:
        return self._request(
            "GET",
            self.settings.docpilot_base_url,
            f"/api/internal/agent/chunks/{chunk_id}",
            self.settings.docpilot_internal_key,
            username,
            tool_name="get_chunk_source",
        )

    def list_documents(self, username: str, kb_id: int) -> list[dict[str, Any]]:
        return self._request(
            "GET",
            self.settings.docpilot_base_url,
            f"/api/internal/agent/knowledge-bases/{kb_id}/documents",
            self.settings.docpilot_internal_key,
            username,
            tool_name="list_documents",
        )

    def get_document_diagnostics(
        self, username: str, document_id: int
    ) -> dict[str, Any]:
        return self._request(
            "GET",
            self.settings.docpilot_base_url,
            f"/api/internal/agent/documents/{document_id}",
            self.settings.docpilot_internal_key,
            username,
            tool_name="get_document_diagnostics",
        )

    def retry_document_parsing(
        self,
        username: str,
        document_id: int,
        approval_id: str | None,
        approval_token: str | None,
    ) -> dict[str, Any]:
        return self._request(
            "POST",
            self.settings.docpilot_base_url,
            f"/api/internal/agent/documents/{document_id}/retry",
            self.settings.docpilot_internal_key,
            username,
            tool_name="retry_document_parsing",
            approval_id=approval_id,
            approval_token=approval_token,
            retryable=False,
        )
