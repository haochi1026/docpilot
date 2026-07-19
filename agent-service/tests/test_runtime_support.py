from __future__ import annotations

import contextvars
import base64
import json
import time
import httpx
from langchain_core.messages import AIMessage, ToolMessage
from langgraph.types import Command

from app.agent_runtime import AgentRuntime, _iterate_in_context, _resume_command
from app.checkpoint import CheckpointProvider
from app.tools import AgentContext
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
    with runtime.checkpoints.open() as checkpointer:
        graph = runtime._build_graph(checkpointer)
        nodes = set(graph.nodes)
    assert {
        "route_intent",
        "plan_required_retrieval",
        "plan_document_operation",
        "advance_document_operation",
        "call_model",
        "policy_gate",
        "execute_tools",
        "verify_result",
        "guard_answer",
    }.issubset(nodes)
    assert runtime.settings.model_run_limit == 8
    assert runtime.settings.tool_run_limit == 12
    assert all(tool.handle_tool_error for tool in runtime.tools)
    runtime.close()


def test_reject_resume_command_explicitly_stops_bypass() -> None:
    command = _resume_command("reject")
    assert command.resume == {"decision": "reject"}


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


def test_trace_finish_is_safe_across_streaming_contexts() -> None:
    traces = TraceClient(settings())
    holder: dict[str, object] = {}

    def start() -> None:
        context, token = traces.begin(
            request_id="cross-context-request",
            thread_id="cross-context-thread",
            input_preview="question",
            metadata={},
        )
        holder["context"] = context
        holder["token"] = token

    contextvars.Context().run(start)
    contextvars.Context().run(
        lambda: traces.finish(
            holder["context"],
            holder["token"],
            status="SUCCESS",
            output_preview="done",
        )
    )
    traces.close()


def test_trace_client_mints_short_lived_agentops_identity() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        token = request.headers["X-Identity-Token"]
        encoded, _signature = token.split(".", 1)
        payload = json.loads(
            base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        )
        assert payload["sub"] == "docpilot-agent"
        assert payload["tenant"] == "tenant-a"
        assert payload["role"] == "OPERATOR"
        assert int(time.time()) < payload["exp"] <= int(time.time()) + 300
        return httpx.Response(200, json={}, request=request)

    local = settings()
    object.__setattr__(local, "agentops_enabled", True)
    object.__setattr__(local, "agentops_api_key", "platform-key")
    object.__setattr__(local, "agentops_identity_token_secret", "identity-secret")
    object.__setattr__(local, "agentops_identity_tenant", "tenant-a")
    traces = TraceClient(local, httpx.Client(transport=httpx.MockTransport(handler)))
    context, token = traces.begin(
        request_id="signed-identity-request",
        thread_id="signed-identity-thread",
        input_preview="question",
        metadata={},
    )
    assert context.exported
    traces.finish(context, token, status="SUCCESS")
    traces.close()


class _ScriptedModel:
    def __init__(self, responses):
        self.responses = list(responses)

    def invoke(self, _messages):
        return self.responses.pop(0)


def _context(thread_id: str, approval: bool = False) -> AgentContext:
    return AgentContext(
        username="alice",
        user_id=1,
        role="ADMIN",
        kb_id=7,
        thread_id=thread_id,
        approval_id="approval-123" if approval else None,
        approval_token="signed-token-123456" if approval else None,
    )


def test_graph_forces_evidence_guard_when_model_skips_retrieval(tmp_path) -> None:
    local = settings()
    object.__setattr__(local, "checkpoint_path", str(tmp_path / "guard.sqlite"))
    runtime = AgentRuntime(local)
    runtime.gateway.search_knowledge_base = lambda *_args: []
    scripted = _ScriptedModel([AIMessage(content="未经检索的确定答案")])
    runtime.bound_model = scripted
    with runtime.checkpoints.open() as saver:
        graph = runtime._build_graph(saver)
        result = graph.invoke(
            {"messages": [{"role": "user", "content": "差旅制度是什么？"}]},
            config={"configurable": {"thread_id": "guard-thread"}},
            context=_context("guard-thread"),
        )
    assert result["verification"] == "insufficient_evidence"
    assert "证据" in result["messages"][-1].content
    assert len(scripted.responses) == 1
    runtime.close()


def test_document_listing_uses_deterministic_operation_subgraph(tmp_path) -> None:
    local = settings()
    object.__setattr__(local, "checkpoint_path", str(tmp_path / "documents.sqlite"))
    runtime = AgentRuntime(local)
    runtime.gateway.list_documents = lambda *_args: [
        {"id": 11, "originalName": "制度.pdf", "status": "SUCCESS"},
        {"id": 12, "originalName": "扫描件.pdf", "status": "FAILED"},
    ]
    with runtime.checkpoints.open() as saver:
        result = runtime._build_graph(saver).invoke(
            {"messages": [{"role": "user", "content": "列出当前知识库所有文档。"}]},
            config={"configurable": {"thread_id": "document-list-thread"}},
            context=_context("document-list-thread"),
        )
    assert result["operation"] == "list_documents"
    assert result["verification"] == "document_answer_ready"
    assert "ID 11" in result["messages"][-1].content
    assert "扫描件.pdf" in result["messages"][-1].content
    runtime.close()


def test_graph_executes_retrieval_then_returns_cited_answer(tmp_path) -> None:
    local = settings()
    object.__setattr__(local, "checkpoint_path", str(tmp_path / "retrieval.sqlite"))
    runtime = AgentRuntime(local)
    runtime.gateway.search_knowledge_base = lambda *_args: [
        {
            "chunkId": 9,
            "documentName": "差旅制度.md",
            "content": "出差前需要审批。",
            "score": 0.91,
        }
    ]
    runtime.model = _ScriptedModel([AIMessage(content="出差前需要审批[1]。")])
    context = _context("retrieval-thread")
    with runtime.checkpoints.open() as saver:
        graph = runtime._build_graph(saver)
        result = graph.invoke(
            {"messages": [{"role": "user", "content": "差旅制度是什么？"}]},
            config={"configurable": {"thread_id": "retrieval-thread"}},
            context=context,
        )
    assert result["verification"] == "answer_verified"
    assert context.evidence.items[0]["chunkId"] == 9
    assert any(isinstance(message, ToolMessage) for message in result["messages"])
    assert result["messages"][-1].content.endswith("[1]。")
    runtime.close()


def test_graph_rejects_answer_with_out_of_range_citation(tmp_path) -> None:
    local = settings()
    object.__setattr__(local, "checkpoint_path", str(tmp_path / "citation.sqlite"))
    runtime = AgentRuntime(local)
    runtime.gateway.search_knowledge_base = lambda *_args: [
        {"chunkId": 9, "documentName": "制度.md", "content": "需要审批", "score": 0.9}
    ]
    runtime.model = _ScriptedModel([AIMessage(content="需要审批[9]。")])
    with runtime.checkpoints.open() as saver:
        result = runtime._build_graph(saver).invoke(
            {"messages": [{"role": "user", "content": "审批规则是什么？"}]},
            config={"configurable": {"thread_id": "citation-thread"}},
            context=_context("citation-thread"),
        )
    assert result["verification"] == "invalid_citations"
    assert "引用完整性校验" in result["messages"][-1].content
    runtime.close()


def test_graph_repairs_missing_citation_without_new_tool_call(tmp_path) -> None:
    local = settings()
    object.__setattr__(local, "checkpoint_path", str(tmp_path / "citation-repair.sqlite"))
    runtime = AgentRuntime(local)
    runtime.gateway.search_knowledge_base = lambda *_args: [
        {
            "chunkId": 9,
            "documentName": "熔断制度.md",
            "content": "连续工具失败后应触发熔断。",
            "score": 0.9,
        }
    ]
    runtime.model = _ScriptedModel(
        [
            AIMessage(content="连续工具失败后应触发熔断。"),
            AIMessage(content="连续工具失败后应触发熔断[1]。"),
        ]
    )
    with runtime.checkpoints.open() as saver:
        result = runtime._build_graph(saver).invoke(
            {"messages": [{"role": "user", "content": "连续工具失败后怎么办？"}]},
            config={"configurable": {"thread_id": "citation-repair-thread"}},
            context=_context("citation-repair-thread"),
        )
    assert result["verification"] == "citations_repaired"
    assert result["messages"][-1].content.endswith("[1]。")
    assert result["tool_calls"] == 1
    runtime.close()


def test_graph_falls_back_to_verbatim_evidence_for_unsupported_tail(tmp_path) -> None:
    local = settings()
    object.__setattr__(local, "checkpoint_path", str(tmp_path / "extractive-fallback.sqlite"))
    runtime = AgentRuntime(local)
    runtime.gateway.search_knowledge_base = lambda *_args: [
        {
            "chunkId": 268,
            "documentName": "差旅制度.md",
            "content": "北京地区住宿标准为每晚 500 元。其他城市按当地制度执行。",
            "score": 0.91,
        }
    ]
    runtime.model = _ScriptedModel(
        [AIMessage(content="北京地区住宿标准为每晚 500 元[1]。具体以财务最终审批为准。")]
    )
    with runtime.checkpoints.open() as saver:
        result = runtime._build_graph(saver).invoke(
            {"messages": [{"role": "user", "content": "北京地区住宿标准是多少？"}]},
            config={"configurable": {"thread_id": "extractive-fallback-thread"}},
            context=_context("extractive-fallback-thread"),
        )
    assert result["verification"] == "evidence_extract_fallback"
    assert result["messages"][-1].content == "北京地区住宿标准为每晚 500 元[1]。"
    runtime.close()


def test_stream_iterator_keeps_captured_trace_context_across_yields() -> None:
    marker: contextvars.ContextVar[str] = contextvars.ContextVar(
        "trace_marker", default="missing"
    )
    marker.set("trace-123")
    execution_context = contextvars.copy_context()
    marker.set("outer-context")

    def values():
        yield marker.get()
        yield marker.get()

    assert list(_iterate_in_context(iter(values()), execution_context)) == [
        "trace-123",
        "trace-123",
    ]


def test_graph_interrupts_write_and_resumes_with_bound_approval(tmp_path) -> None:
    local = settings()
    object.__setattr__(local, "checkpoint_path", str(tmp_path / "hitl.sqlite"))
    runtime = AgentRuntime(local)
    calls: list[tuple] = []
    runtime.gateway.get_document_diagnostics = lambda *_args: {
        "id": 11,
        "originalName": "failed.pdf",
        "status": "FAILED",
        "errorMessage": "no text",
    }
    runtime.gateway.retry_document_parsing = lambda *args: calls.append(args) or {
        "id": 11,
        "status": "PENDING",
    }
    config = {"configurable": {"thread_id": "approval-thread"}}
    with runtime.checkpoints.open() as saver:
        graph = runtime._build_graph(saver)
        graph.invoke(
            {"messages": [{"role": "user", "content": "重试失败文档 11"}]},
            config=config,
            context=_context("approval-thread"),
        )
        assert any(task.interrupts for task in graph.get_state(config).tasks)
        result = graph.invoke(
            Command(resume={"decision": "approve"}),
            config=config,
            context=_context("approval-thread", approval=True),
        )
    assert calls == [("alice", 11, "approval-123", "signed-token-123456")]
    assert result["verification"] == "write_completed"
    assert "文档 ID：11" in result["messages"][-1].content
    assert "异步处理队列" in result["messages"][-1].content
    runtime.close()
