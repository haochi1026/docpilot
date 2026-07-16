from __future__ import annotations

import json
from collections.abc import Iterator

from fastapi import FastAPI
from fastapi.responses import StreamingResponse

from .agent_runtime import AgentRuntime
from .schemas import AgentChatRequest, AgentEvent
from .settings import Settings


def create_app(runtime: AgentRuntime | None = None) -> FastAPI:
    application = FastAPI(title="DocPilot Agent Service", version="1.0.0")
    application.state.runtime = runtime or AgentRuntime(Settings.from_env())

    @application.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP"}

    @application.post("/v1/agent/chat/stream")
    def chat(request: AgentChatRequest) -> StreamingResponse:
        def ndjson() -> Iterator[str]:
            try:
                for event in application.state.runtime.stream(request):
                    yield event.model_dump_json() + "\n"
            except Exception as exc:
                error = AgentEvent(type="error", data=str(exc) or "Agent execution failed")
                yield json.dumps(error.model_dump(), ensure_ascii=False) + "\n"

        return StreamingResponse(ndjson(), media_type="application/x-ndjson")

    return application


app = create_app()

