from __future__ import annotations

import os
import sqlite3
from collections.abc import Iterator
from typing import Any

from langchain.agents import create_agent
from langchain.agents.middleware import HumanInTheLoopMiddleware
from langchain_ollama import ChatOllama
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.types import Command

from .gateway import InternalGateway
from .schemas import AgentChatRequest, AgentEvent
from .settings import Settings
from .tools import AgentContext, build_tools


SYSTEM_PROMPT = """你是 DocPilot 的知识与实验室事务助手。
回答文档问题前优先调用知识库检索工具，不得编造未检索到的制度、数字或结论；引用资料时使用[1]、[2]标记。
只能在当前登录用户的权限范围内调用工具。知识库 ACL、预约事务、幂等和资源冲突由 Java 服务确定，
不得根据语言模型推测替代业务校验。查询类工具可直接执行；预约、取消、签到属于有副作用操作，
必须在界面展示参数并获得用户批准后才能继续。回答使用中文，先给结论，再给必要依据。"""


class AgentRuntime:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.gateway = InternalGateway(settings)
        self.tools = build_tools(self.gateway, settings.yanyue_enabled)
        self.model = ChatOllama(
            model=settings.ollama_model,
            base_url=settings.ollama_base_url,
            temperature=0,
        )
        self.middleware = []
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

    def _open_graph(self) -> tuple[Any, sqlite3.Connection]:
        parent = os.path.dirname(self.settings.checkpoint_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        connection = sqlite3.connect(
            self.settings.checkpoint_path,
            timeout=30,
            check_same_thread=False,
        )
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA busy_timeout=30000")
        graph = create_agent(
            model=self.model,
            tools=self.tools,
            system_prompt=SYSTEM_PROMPT,
            context_schema=AgentContext,
            middleware=self.middleware,
            checkpointer=SqliteSaver(connection),
        )
        return graph, connection

    def stream(self, request: AgentChatRequest) -> Iterator[AgentEvent]:
        context = AgentContext(
            username=request.username,
            user_id=request.user_id,
            role=request.role,
            kb_id=request.kb_id,
            thread_id=request.thread_id,
        )
        config = {"configurable": {"thread_id": request.thread_id}}
        graph, connection = self._open_graph()
        try:
            graph_input: Any
            if request.decision:
                graph_input = Command(
                    resume={"decisions": [{"type": request.decision}]}
                )
                yield AgentEvent(type="status", data="正在恢复已暂停的 Agent 任务")
            else:
                existing = graph.get_state(config)
                prior_messages = [] if existing.values else request.history
                graph_input = {
                    "messages": prior_messages
                    + [{"role": "user", "content": request.message}]
                }
                yield AgentEvent(type="status", data="Agent 正在分析任务")

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
                        yield AgentEvent(type="token", data=text)
                elif part_type == "custom":
                    if isinstance(data, dict) and data.get("status"):
                        yield AgentEvent(type="status", data=str(data["status"]))
                elif part_type == "updates" and isinstance(data, dict):
                    tool_names = [
                        name
                        for name in data.keys()
                        if name not in {"model", "tools", "HumanInTheLoopMiddleware.after_model"}
                    ]
                    if tool_names:
                        yield AgentEvent(type="status", data="正在执行：" + "、".join(tool_names))

            snapshot = graph.get_state(config)
            interrupts = [
                interrupt
                for task in snapshot.tasks
                for interrupt in getattr(task, "interrupts", ())
            ]
            if interrupts:
                payload = [getattr(interrupt, "value", interrupt) for interrupt in interrupts]
                yield AgentEvent(type="approval", data=payload)
                return

            answer = _last_assistant_text(snapshot.values)
            yield AgentEvent(type="replace", data=answer)
            yield AgentEvent(type="sources", data=context.evidence.items)
            yield AgentEvent(
                type="done",
                data={"answer": answer, "sources": context.evidence.items},
            )
        finally:
            connection.close()


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
