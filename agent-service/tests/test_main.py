from __future__ import annotations

from fastapi.testclient import TestClient

from app.main import create_app
from app.schemas import AgentEvaluationResponse, AgentEvent

from test_gateway import settings


class FakeRuntime:
    def __init__(self) -> None:
        self.closed = False

    def stream(self, request):
        yield AgentEvent(type="replace", data=f"answer:{request.message}")
        yield AgentEvent(type="done", data={"request_id": request.request_id})

    def evaluate(self, request):
        return AgentEvaluationResponse(
            request_id=request.request_id or "generated",
            status="SUCCESS",
            answer="answer",
            sources=[],
            event_types=["replace", "done"],
            approval_requested=False,
        )

    def ready(self):
        return {"status": "UP", "checkpoint": "sqlite", "model": "test"}

    def close(self) -> None:
        self.closed = True


def _payload() -> dict:
    return {
        "request_id": "request-1",
        "thread_id": "thread-1",
        "username": "alice",
        "user_id": 1,
        "role": "USER",
        "kb_id": 1,
        "message": "question",
    }


def test_health_metrics_and_stream_service_auth() -> None:
    runtime = FakeRuntime()
    app = create_app(runtime, settings())
    with TestClient(app) as client:
        assert client.get("/health/live").status_code == 200
        assert client.get("/health/ready").json()["checkpoint"] == "sqlite"
        assert "docpilot_agent" in client.get("/metrics").text
        assert client.post("/v1/agent/chat/stream", json=_payload()).status_code == 401
        response = client.post(
            "/v1/agent/chat/stream",
            headers={"X-Agent-Service-Key": "service-key"},
            json=_payload(),
        )
        assert response.status_code == 200
        assert '"type":"done"' in response.text
    assert runtime.closed is True


def test_evaluation_adapter_requires_service_auth() -> None:
    app = create_app(FakeRuntime(), settings())
    with TestClient(app) as client:
        response = client.post(
            "/v1/agent/evaluate",
            headers={"X-Agent-Service-Key": "service-key"},
            json=_payload(),
        )
        assert response.status_code == 200
        assert response.json()["status"] == "SUCCESS"
