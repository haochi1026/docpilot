from __future__ import annotations

import json
from collections.abc import AsyncIterator, Iterator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI
from fastapi.responses import PlainTextResponse, StreamingResponse

from .agent_runtime import AgentRuntime
from .metrics import metrics
from .schemas import AgentChatRequest, AgentEvaluationResponse, AgentEvent
from .security import ServiceKeyGuard
from .settings import Settings


def create_app(
    runtime: AgentRuntime | None = None,
    settings: Settings | None = None,
) -> FastAPI:
    resolved_settings = settings or Settings.from_env()
    resolved_runtime = runtime or AgentRuntime(resolved_settings)
    guard = ServiceKeyGuard(resolved_settings)

    @asynccontextmanager
    async def lifespan(_application: FastAPI) -> AsyncIterator[None]:
        yield
        resolved_runtime.close()

    application = FastAPI(
        title="DocPilot Agent Service",
        version="2.0.0",
        lifespan=lifespan,
    )
    application.state.runtime = resolved_runtime
    application.state.settings = resolved_settings

    @application.get("/health/live")
    def liveness() -> dict[str, str]:
        return {"status": "UP"}

    @application.get("/health/ready")
    def readiness() -> dict[str, str]:
        return resolved_runtime.ready()

    @application.get("/health")
    def health_alias() -> dict[str, str]:
        return liveness()

    @application.get("/metrics", response_class=PlainTextResponse)
    def prometheus_metrics() -> str:
        return metrics.render()

    @application.post(
        "/v1/agent/chat/stream",
        dependencies=[Depends(guard)],
    )
    def chat(request: AgentChatRequest) -> StreamingResponse:
        def ndjson() -> Iterator[str]:
            try:
                for event in application.state.runtime.stream(request):
                    yield event.model_dump_json() + "\n"
            except Exception as exc:
                error = AgentEvent(
                    type="error",
                    data=_public_error(exc),
                )
                yield json.dumps(error.model_dump(), ensure_ascii=False) + "\n"

        return StreamingResponse(ndjson(), media_type="application/x-ndjson")

    @application.post(
        "/v1/agent/evaluate",
        response_model=AgentEvaluationResponse,
        dependencies=[Depends(guard)],
    )
    def evaluate(request: AgentChatRequest) -> AgentEvaluationResponse:
        return application.state.runtime.evaluate(request)

    return application


def _public_error(exc: Exception) -> str:
    if isinstance(exc, ValueError):
        return str(exc)
    return "Agent 执行失败，请稍后重试并使用 request_id 查询 Trace。"


app = create_app()
