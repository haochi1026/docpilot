from __future__ import annotations

import httpx

from app.gateway import InternalGateway
from app.settings import Settings


def settings() -> Settings:
    return Settings(
        environment="test",
        agent_service_key="service-key",
        ollama_base_url="http://ollama",
        ollama_model="test",
        docpilot_base_url="http://docpilot",
        docpilot_internal_key="doc-key",
        checkpoint_backend="sqlite",
        checkpoint_path=":memory:",
        checkpoint_dsn="",
        request_timeout_seconds=1,
        gateway_max_retries=2,
        gateway_backoff_seconds=0,
        circuit_failure_threshold=2,
        circuit_recovery_seconds=30,
        model_run_limit=8,
        tool_run_limit=12,
        agentops_enabled=False,
        agentops_base_url="http://agentops",
        agentops_api_key="",
        agentops_timeout_seconds=1,
    )


def test_search_forwards_identity_and_scope() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Agent-Key"] == "doc-key"
        assert request.headers["X-Username"] == "alice"
        assert request.url.path == "/api/internal/agent/search"
        return httpx.Response(200, json=[{"chunkId": 7, "content": "source"}])

    gateway = InternalGateway(settings(), httpx.Client(transport=httpx.MockTransport(handler)))
    hits = gateway.search_knowledge_base("alice", 3, "question", 4)
    assert hits[0]["chunkId"] == 7


def test_document_retry_gateway_requires_approved_header() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Agent-Key"] == "doc-key"
        assert request.headers["X-Agent-Approval-Id"] == "approval-123"
        assert request.headers["X-Agent-Approval-Token"] == "signed-token-123456"
        assert request.url.path == "/api/internal/agent/documents/11/retry"
        return httpx.Response(200, json={"id": 11, "status": "PENDING"})

    gateway = InternalGateway(settings(), httpx.Client(transport=httpx.MockTransport(handler)))
    result = gateway.retry_document_parsing(
        "alice", 11, "approval-123", "signed-token-123456"
    )
    assert result["id"] == 11


def test_gateway_retries_transient_read_failures() -> None:
    attempts = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            return httpx.Response(503, json={"message": "temporary"})
        return httpx.Response(200, json=[])

    gateway = InternalGateway(
        settings(), httpx.Client(transport=httpx.MockTransport(handler))
    )
    assert gateway.list_knowledge_bases("alice") == []
    assert attempts == 3


def test_gateway_opens_circuit_after_repeated_failures() -> None:
    attempts = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal attempts
        attempts += 1
        return httpx.Response(503, json={"message": "down"})

    local = settings()
    gateway = InternalGateway(
        local, httpx.Client(transport=httpx.MockTransport(handler))
    )
    for _ in range(2):
        try:
            gateway.list_knowledge_bases("alice")
        except RuntimeError:
            pass
    before = attempts
    try:
        gateway.list_knowledge_bases("alice")
    except RuntimeError as exc:
        assert "circuit" in str(exc)
    assert attempts == before
