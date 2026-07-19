from __future__ import annotations

import json
import asyncio
from collections.abc import AsyncIterator, Iterator
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Header, HTTPException
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
        stop = asyncio.Event()

        async def cleanup_loop() -> None:
            while not stop.is_set():
                try:
                    await asyncio.wait_for(stop.wait(), timeout=resolved_settings.checkpoint_cleanup_interval_seconds)
                except asyncio.TimeoutError:
                    await asyncio.to_thread(
                        resolved_runtime.checkpoints.cleanup,
                        resolved_settings.checkpoint_retention_days,
                    )

        cleanup_task = asyncio.create_task(cleanup_loop())
        try:
            yield
        finally:
            stop.set()
            cleanup_task.cancel()
            await asyncio.gather(cleanup_task, return_exceptions=True)
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

    @application.delete(
        "/v1/agent/threads/{thread_id}",
        dependencies=[Depends(guard)],
        status_code=204,
    )
    def delete_thread(thread_id: str) -> None:
        application.state.runtime.delete_thread(thread_id)

    @application.post("/v1/admin/checkpoints/cleanup")
    def cleanup_checkpoints(x_agent_key: str = Header(default="")) -> dict[str, int]:
        if x_agent_key != resolved_settings.agent_service_key:
            raise HTTPException(status_code=401, detail="invalid agent credential")
        return {"deleted": application.state.runtime.checkpoints.cleanup(resolved_settings.checkpoint_retention_days)}

    return application


def _public_error(exc: Exception) -> str:
    if isinstance(exc, ValueError):
        return str(exc)
    return "Agent 执行失败，请稍后重试并使用 request_id 查询 Trace。"


app = create_app()
