from __future__ import annotations

import httpx

from app.agent_runtime import AgentRuntime
from app.checkpoint import CheckpointProvider
from app.tracing import TraceClient

from test_gateway import settings


def test_sqlite_checkpoint_provider_creates_local_store(tmp_path) -> None:
    local = settings()
    object.__setattr__(local, "checkpoint_path", str(tmp_path / "checkpoints.sqlite"))
    with CheckpointProvider(local).open() as saver:
        assert saver is not None
    assert (tmp_path / "checkpoints.sqlite").exists()


def test_runtime_installs_model_and_tool_call_limits() -> None:
    runtime = AgentRuntime(settings())
    middleware = {item.__class__.__name__ for item in runtime.middleware}
    assert "ModelCallLimitMiddleware" in middleware
    assert "ToolCallLimitMiddleware" in middleware
    assert "HumanInTheLoopMiddleware" in middleware
    runtime.close()


def test_trace_export_redacts_secrets_and_records_span() -> None:
    payloads: list[dict] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payloads.append(__import__("json").loads(request.content))
        return httpx.Response(200, json={}, request=request)

    local = settings()
    object.__setattr__(local, "agentops_enabled", True)
    object.__setattr__(local, "agentops_api_key", "platform-key")
    client = httpx.Client(transport=httpx.MockTransport(handler))
    traces = TraceClient(local, client)
    context, token = traces.begin(
        request_id="request-1",
        thread_id="thread-1",
        input_preview="question",
        metadata={"model": "mock"},
    )
    traces.span(
        name="search",
        kind="TOOL",
        status="SUCCESS",
        duration_ms=10,
        input_data={"api_key": "do-not-store", "query": "x"},
        output_data={"count": 1},
    )
    traces.finish(context, token, status="SUCCESS", output_preview="answer")
    assert payloads[1]["input"]["api_key"] == "***"
    assert len(payloads) == 3
    traces.close()
