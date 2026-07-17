from __future__ import annotations

import uuid
from collections.abc import Iterator
from typing import Any

from langchain.agents import create_agent
from langchain.agents.middleware import (
    HumanInTheLoopMiddleware,
    ModelCallLimitMiddleware,
    ToolCallLimitMiddleware,
)
from langchain_ollama import ChatOllama
from langgraph.types import Command

from .checkpoint import CheckpointProvider
from .gateway import InternalGateway
from .metrics import metrics
from .schemas import AgentChatRequest, AgentEvaluationResponse, AgentEvent
from .settings import Settings
from .tools import AgentContext, build_tools
from .tracing import TraceClient


SYSTEM_PROMPT = """你是 DocPilot 的知识库与实验室事务助手。
回答文档问题前优先调用知识库检索工具，不得编造未检索到的制度、数字或结论；引用资料时使用 [1]、[2] 标记。
只能在当前登录用户的权限范围内调用工具。知识库 ACL、预约事务、幂等和资源冲突由 Java 服务确定，不得根据语言模型推测替代业务校验。
查询类工具可以直接执行；预约、取消、签到属于有副作用操作，必须展示参数并获得用户批准后才能继续。
工具失败时先解释可恢复原因，不要把内部密钥、堆栈或服务地址暴露给用户。回答使用中文，先给结论，再给必要依据。"""


class AgentRuntime:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.trace_client = TraceClient(settings)
        self.gateway = InternalGateway(settings, trace_client=self.trace_client)
        self.checkpoints = CheckpointProvider(settings)
        self.tools = build_tools(self.gateway, settings.yanyue_enabled)
        self.model = ChatOllama(
            model=settings.ollama_model,
            base_url=settings.ollama_base_url,
            temperature=0,
        )
        self.middleware: list[Any] = [
            ModelCallLimitMiddleware(
                run_limit=settings.model_run_limit,
                exit_behavior="end",
            ),
            ToolCallLimitMiddleware(
                run_limit=settings.tool_run_limit,
                exit_behavior="continue",
            ),
        ]
        if settings.yanyue_enabled:
            self.middleware.append(
                HumanInTheLoopMiddleware(
                    interrupt_on={
                        "create_reservation": {
                            "allowed_decisions": ["approve", "reject"]
                        },
                        "cancel_reservation": {
                            "allowed_decisions": ["approve", "reject"]
                        },
                        "check_in_reservation": {
                            "allowed_decisions": ["approve", "reject"]
                        },
                    },
                    description_prefix="以下操作会修改研约业务数据，请确认",
                )
            )

    def close(self) -> None:
        self.gateway.close()
        self.trace_client.close()

    def ready(self) -> dict[str, str]:
        # Opening the saver verifies local file access or PostgreSQL connectivity.
        with self.checkpoints.open():
            pass
        return {
            "status": "UP",
            "checkpoint": self.settings.checkpoint_backend,
            "model": self.settings.ollama_model,
        }

    def _build_graph(self, checkpointer: Any) -> Any:
        return create_agent(
            model=self.model,
            tools=self.tools,
            system_prompt=SYSTEM_PROMPT,
            context_schema=AgentContext,
            middleware=self.middleware,
            checkpointer=checkpointer,
        )

    def stream(self, request: AgentChatRequest) -> Iterator[AgentEvent]:
        request_id = request.request_id or str(uuid.uuid4())
        trace, trace_token = self.trace_client.begin(
            request_id=request_id,
            thread_id=request.thread_id,
            input_preview=request.message or f"decision:{request.decision}",
            metadata={
                "kb_id": request.kb_id,
                "user_id": request.user_id,
                "role": request.role,
                "checkpoint_backend": self.settings.checkpoint_backend,
                "model": self.settings.ollama_model,
            },
        )
        context = AgentContext(
            username=request.username,
            user_id=request.user_id,
            role=request.role,
            kb_id=request.kb_id,
            thread_id=request.thread_id,
        )
        config = {"configurable": {"thread_id": request.thread_id}}
        trace_finished = False
        try:
            with self.checkpoints.open() as checkpointer:
                graph = self._build_graph(checkpointer)
                if request.decision:
                    existing = graph.get_state(config)
                    if not _interrupts(existing):
                        raise ValueError("当前 thread_id 没有等待审批的 Agent 任务")
                    graph_input: Any = Command(
                        resume={"decisions": [{"type": request.decision}]}
                    )
                    yield self._event("status", "正在恢复已暂停的 Agent 任务")
                else:
                    existing = graph.get_state(config)
                    prior_messages = [] if existing.values else request.history
                    graph_input = {
                        "messages": prior_messages
                        + [{"role": "user", "content": request.message}]
                    }
                    yield self._event("status", "Agent 正在分析任务")

                for part in graph.stream(
                    graph_input,
                    config=config,
                    context=context,
                    stream_mode=["messages", "updates", "custom"],
                    version="v2",
                ):
                    part_type = part.get("type")
                    data = part.get("data")
                    if part_type == "messages":
                        message, _metadata = data
                        text = _message_text(message)
                        if text:
                            yield self._event("token", text)
                    elif part_type == "custom":
                        if isinstance(data, dict) and data.get("status"):
                            yield self._event("status", str(data["status"]))
                    elif part_type == "updates" and isinstance(data, dict):
                        tool_names = [
                            name
                            for name in data.keys()
                            if name
                            not in {
                                "model",
                                "tools",
                                "HumanInTheLoopMiddleware.after_model",
                            }
                        ]
                        if tool_names:
                            yield self._event("status", "正在执行：" + "、".join(tool_names))

                snapshot = graph.get_state(config)
                interrupts = _interrupts(snapshot)
                if interrupts:
                    payload = [getattr(item, "value", item) for item in interrupts]
                    yield self._event("approval", payload)
                    self.trace_client.finish(
                        trace,
                        trace_token,
                        status="INTERRUPTED",
                        output_preview="waiting for human approval",
                    )
                    trace_finished = True
                    return

                answer = _last_assistant_text(snapshot.values)
                yield self._event("replace", answer)
                yield self._event("sources", context.evidence.items)
                yield self._event(
                    "done",
                    {
                        "request_id": request_id,
                        "trace_id": trace.trace_id,
                        "answer": answer,
                        "sources": context.evidence.items,
                    },
                )
                self.trace_client.finish(
                    trace,
                    trace_token,
                    status="SUCCESS",
                    output_preview=answer,
                )
                trace_finished = True
        except Exception as exc:
            if not trace_finished:
                self.trace_client.finish(
                    trace,
                    trace_token,
                    status="ERROR",
                    error=str(exc),
                )
                trace_finished = True
            metrics.inc(
                "docpilot_agent_events_total",
                event="run",
                status="error",
            )
            raise
        finally:
            if not trace_finished:
                self.trace_client.finish(
                    trace,
                    trace_token,
                    status="CANCELLED",
                    error="stream closed before completion",
                )

    def evaluate(self, request: AgentChatRequest) -> AgentEvaluationResponse:
        request_id = request.request_id or str(uuid.uuid4())
        request.request_id = request_id
        answer = ""
        sources: list[dict[str, Any]] = []
        event_types: list[str] = []
        approval = False
        try:
            for event in self.stream(request):
                event_types.append(event.type)
                if event.type == "replace":
                    answer = str(event.data or "")
                elif event.type == "sources" and isinstance(event.data, list):
                    sources = event.data
                elif event.type == "approval":
                    approval = True
            return AgentEvaluationResponse(
                request_id=request_id,
                status="INTERRUPTED" if approval else "SUCCESS",
                answer=answer,
                sources=sources,
                event_types=event_types,
                approval_requested=approval,
            )
        except Exception as exc:
            return AgentEvaluationResponse(
                request_id=request_id,
                status="ERROR",
                answer=answer,
                sources=sources,
                event_types=event_types,
                approval_requested=approval,
                error=str(exc),
            )

    def _event(self, event_type: str, data: Any) -> AgentEvent:
        metrics.inc(
            "docpilot_agent_events_total",
            event=event_type,
            status="emitted",
        )
        return AgentEvent(type=event_type, data=data)


def _interrupts(snapshot: Any) -> list[Any]:
    return [
        interrupt
        for task in getattr(snapshot, "tasks", ())
        for interrupt in getattr(task, "interrupts", ())
    ]


def _message_text(message: Any) -> str:
    text = getattr(message, "text", None)
    if isinstance(text, str):
        return text
    content = getattr(message, "content", None)
    return content if isinstance(content, str) else ""


def _last_assistant_text(state: Any) -> str:
    messages = state.get("messages", []) if isinstance(state, dict) else []
    for message in reversed(messages):
        message_type = getattr(message, "type", "")
        if message_type in {"ai", "assistant"}:
            text = _message_text(message)
            if text:
                return text
    return "Agent 已完成执行，但模型未返回可展示文本。"
