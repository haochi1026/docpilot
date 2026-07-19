from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from langchain.tools import ToolRuntime, tool
from langchain_core.tools import ToolException

from .gateway import GatewayError, InternalGateway


@dataclass
class EvidenceCollector:
    items: list[dict[str, Any]] = field(default_factory=list)
    _ids: set[int] = field(default_factory=set)

    def add_all(self, hits: list[dict[str, Any]]) -> None:
        for hit in hits:
            chunk_id = int(hit.get("chunkId", 0) or 0)
            if chunk_id and chunk_id not in self._ids:
                self._ids.add(chunk_id)
                self.items.append(hit)


@dataclass
class AgentContext:
    username: str
    user_id: int
    role: str
    kb_id: int
    thread_id: str
    approval_id: str | None = None
    approval_token: str | None = None
    evidence: EvidenceCollector = field(default_factory=EvidenceCollector)


def build_tools(gateway: InternalGateway) -> list[Any]:
    @tool
    def search_knowledge_base(
        query: str, runtime: ToolRuntime[AgentContext], top_k: int = 4
    ) -> list[dict[str, Any]]:
        """Search the current authorized DocPilot knowledge base for relevant source chunks."""
        runtime.stream_writer({"status": "正在检索知识库"})
        hits = _invoke_gateway(
            "search_knowledge_base",
            lambda: gateway.search_knowledge_base(
                runtime.context.username,
                runtime.context.kb_id,
                query.strip(),
                max(1, min(top_k, 8)),
            ),
        )
        runtime.context.evidence.add_all(hits)
        return hits

    @tool
    def get_chunk_source(
        chunk_id: int, runtime: ToolRuntime[AgentContext]
    ) -> dict[str, Any]:
        """Read one cited DocPilot chunk and its document/page metadata after ACL validation."""
        runtime.stream_writer({"status": f"正在核对引用片段 {chunk_id}"})
        chunk = _invoke_gateway(
            "get_chunk_source",
            lambda: gateway.get_chunk(runtime.context.username, chunk_id),
        )
        runtime.context.evidence.add_all([chunk])
        return chunk

    @tool
    def list_accessible_knowledge_bases(
        runtime: ToolRuntime[AgentContext],
    ) -> list[dict[str, Any]]:
        """List DocPilot knowledge bases accessible to the signed-in user."""
        return _invoke_gateway(
            "list_accessible_knowledge_bases",
            lambda: gateway.list_knowledge_bases(runtime.context.username),
        )

    @tool
    def list_documents(
        runtime: ToolRuntime[AgentContext], status: str = ""
    ) -> list[dict[str, Any]]:
        """List documents in the current authorized knowledge base, optionally filtered by status."""
        runtime.stream_writer({"status": "正在检查知识库文档状态"})
        items = _invoke_gateway(
            "list_documents",
            lambda: gateway.list_documents(
                runtime.context.username, runtime.context.kb_id
            ),
        )
        normalized = status.strip().upper()
        if normalized:
            items = [
                item
                for item in items
                if str(item.get("status", "")).upper() == normalized
            ]
        return items

    @tool
    def get_document_diagnostics(
        document_id: int, runtime: ToolRuntime[AgentContext]
    ) -> dict[str, Any]:
        """Inspect one document's parse status, failure reason, version and chunk count after ACL validation."""
        runtime.stream_writer({"status": f"正在诊断文档 {document_id}"})
        return _invoke_gateway(
            "get_document_diagnostics",
            lambda: gateway.get_document_diagnostics(
                runtime.context.username, document_id
            ),
        )

    @tool
    def retry_document_parsing(
        document_id: int, runtime: ToolRuntime[AgentContext]
    ) -> dict[str, Any]:
        """Retry a FAILED DocPilot document parse. This state-changing action requires explicit user approval."""
        runtime.stream_writer({"status": f"正在重新发布文档 {document_id} 的解析任务"})
        return _invoke_gateway(
            "retry_document_parsing",
            lambda: gateway.retry_document_parsing(
                runtime.context.username,
                document_id,
                runtime.context.approval_id,
                runtime.context.approval_token,
            ),
        )

    tools = [
        search_knowledge_base,
        get_chunk_source,
        list_accessible_knowledge_bases,
        list_documents,
        get_document_diagnostics,
        retry_document_parsing,
    ]
    for registered_tool in tools:
        registered_tool.handle_tool_error = True
    return tools


def _invoke_gateway(name: str, action: Callable[[], Any]) -> Any:
    try:
        return action()
    except GatewayError as exc:
        raise ToolException(
            f"{name} 执行失败：目标不存在、当前用户无权限、状态不允许或下游服务暂不可用"
        ) from exc
