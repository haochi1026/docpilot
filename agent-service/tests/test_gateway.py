from __future__ import annotations

import httpx

from app.gateway import InternalGateway
from app.settings import Settings


def settings() -> Settings:
    return Settings(
        ollama_base_url="http://ollama",
        ollama_model="test",
        docpilot_base_url="http://docpilot",
        docpilot_internal_key="doc-key",
        yanyue_enabled=True,
        yanyue_base_url="http://yanyue",
        yanyue_internal_key="yy-key",
        checkpoint_path=":memory:",
        request_timeout_seconds=1,
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


def test_write_gateway_requires_approved_header() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Agent-Key"] == "yy-key"
        assert request.headers["X-Agent-Approval"] == "approved"
        return httpx.Response(200, json={"id": 11, "status": "BOOKED"})

    gateway = InternalGateway(settings(), httpx.Client(transport=httpx.MockTransport(handler)))
    result = gateway.create_reservation("alice", {"requestId": "r1"})
    assert result["id"] == 11

