from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from typing import Any

from langchain.tools import ToolRuntime, tool

from .gateway import InternalGateway


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
    evidence: EvidenceCollector = field(default_factory=EvidenceCollector)


def build_tools(gateway: InternalGateway, yanyue_enabled: bool) -> list[Any]:
    @tool
    def search_knowledge_base(
        query: str, runtime: ToolRuntime[AgentContext], top_k: int = 4
    ) -> list[dict[str, Any]]:
        """Search the current authorized DocPilot knowledge base for relevant source chunks."""
        runtime.stream_writer({"status": "正在检索知识库"})
        hits = gateway.search_knowledge_base(
            runtime.context.username,
            runtime.context.kb_id,
            query.strip(),
            max(1, min(top_k, 8)),
        )
        runtime.context.evidence.add_all(hits)
        return hits

    @tool
    def get_chunk_source(
        chunk_id: int, runtime: ToolRuntime[AgentContext]
    ) -> dict[str, Any]:
        """Read one cited DocPilot chunk and its document/page metadata after ACL validation."""
        runtime.stream_writer({"status": f"正在核对引用片段 {chunk_id}"})
        chunk = gateway.get_chunk(runtime.context.username, chunk_id)
        runtime.context.evidence.add_all([chunk])
        return chunk

    @tool
    def list_accessible_knowledge_bases(
        runtime: ToolRuntime[AgentContext],
    ) -> list[dict[str, Any]]:
        """List DocPilot knowledge bases accessible to the signed-in user."""
        return gateway.list_knowledge_bases(runtime.context.username)

    tools: list[Any] = [
        search_knowledge_base,
        get_chunk_source,
        list_accessible_knowledge_bases,
    ]

    if not yanyue_enabled:
        return tools

    @tool
    def list_lab_resources(runtime: ToolRuntime[AgentContext]) -> list[dict[str, Any]]:
        """List enabled laboratory equipment, rooms, and desks from Yanyue."""
        return gateway.list_lab_resources(runtime.context.username)

    @tool
    def query_resource_availability(
        resource_id: int, date: str, runtime: ToolRuntime[AgentContext]
    ) -> dict[str, Any]:
        """Query occupied 30-minute slots for one laboratory resource on an ISO date."""
        return gateway.resource_availability(runtime.context.username, resource_id, date)

    @tool
    def list_my_reservations(runtime: ToolRuntime[AgentContext]) -> list[dict[str, Any]]:
        """List reservations owned by the signed-in user."""
        return gateway.my_reservations(runtime.context.username)

    @tool
    def create_reservation(
        resource_id: int,
        reserve_date: str,
        start_slot: int,
        end_slot: int,
        purpose: str,
        runtime: ToolRuntime[AgentContext],
    ) -> dict[str, Any]:
        """Create a Yanyue reservation. This write must be explicitly approved by the user."""
        fingerprint = (
            f"{runtime.context.thread_id}:{runtime.context.username}:{resource_id}:"
            f"{reserve_date}:{start_slot}:{end_slot}:{purpose.strip()}"
        )
        request_id = "agent-" + hashlib.sha256(fingerprint.encode("utf-8")).hexdigest()[:32]
        return gateway.create_reservation(
            runtime.context.username,
            {
                "requestId": request_id,
                "resourceId": resource_id,
                "reserveDate": reserve_date,
                "startSlot": start_slot,
                "endSlot": end_slot,
                "purpose": purpose.strip(),
            },
        )

    @tool
    def cancel_reservation(
        reservation_id: int, runtime: ToolRuntime[AgentContext]
    ) -> dict[str, Any]:
        """Cancel the signed-in user's Yanyue reservation after explicit approval."""
        gateway.cancel_reservation(runtime.context.username, reservation_id)
        return {"reservationId": reservation_id, "status": "CANCELLED"}

    @tool
    def check_in_reservation(
        reservation_id: int, runtime: ToolRuntime[AgentContext]
    ) -> dict[str, Any]:
        """Check in to a Yanyue reservation after explicit approval."""
        return gateway.check_in(runtime.context.username, reservation_id)

    tools.extend(
        [
            list_lab_resources,
            query_resource_availability,
            list_my_reservations,
            create_reservation,
            cancel_reservation,
            check_in_reservation,
        ]
    )
    return tools

