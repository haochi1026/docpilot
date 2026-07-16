from __future__ import annotations

from typing import Any

import httpx

from .settings import Settings


class GatewayError(RuntimeError):
    pass


class InternalGateway:
    """Calls deterministic Java services; the model never receives service credentials."""

    def __init__(self, settings: Settings, client: httpx.Client | None = None) -> None:
        self.settings = settings
        self.client = client or httpx.Client(timeout=settings.request_timeout_seconds)

    def _request(
        self,
        method: str,
        base_url: str,
        path: str,
        key: str,
        username: str,
        *,
        params: dict[str, Any] | None = None,
        json: dict[str, Any] | None = None,
        approved: bool = False,
    ) -> Any:
        headers = {"X-Agent-Key": key, "X-Username": username}
        if approved:
            headers["X-Agent-Approval"] = "approved"
        try:
            response = self.client.request(
                method,
                base_url.rstrip("/") + path,
                headers=headers,
                params=params,
                json=json,
            )
            response.raise_for_status()
        except httpx.HTTPStatusError as exc:
            try:
                detail = exc.response.json().get("message")
            except Exception:
                detail = exc.response.text
            raise GatewayError(detail or f"tool gateway returned {exc.response.status_code}") from exc
        except httpx.HTTPError as exc:
            raise GatewayError(f"tool gateway unavailable: {exc}") from exc
        if response.status_code == 204 or not response.content:
            return None
        return response.json()

    def list_knowledge_bases(self, username: str) -> list[dict[str, Any]]:
        return self._request(
            "GET",
            self.settings.docpilot_base_url,
            "/api/internal/agent/knowledge-bases",
            self.settings.docpilot_internal_key,
            username,
        )

    def search_knowledge_base(
        self, username: str, kb_id: int, query: str, top_k: int
    ) -> list[dict[str, Any]]:
        return self._request(
            "POST",
            self.settings.docpilot_base_url,
            "/api/internal/agent/search",
            self.settings.docpilot_internal_key,
            username,
            json={"kbId": kb_id, "query": query, "topK": top_k},
        )

    def get_chunk(self, username: str, chunk_id: int) -> dict[str, Any]:
        return self._request(
            "GET",
            self.settings.docpilot_base_url,
            f"/api/internal/agent/chunks/{chunk_id}",
            self.settings.docpilot_internal_key,
            username,
        )

    def list_lab_resources(self, username: str) -> list[dict[str, Any]]:
        return self._request(
            "GET",
            self.settings.yanyue_base_url,
            "/api/internal/agent/resources",
            self.settings.yanyue_internal_key,
            username,
        )

    def resource_availability(
        self, username: str, resource_id: int, date: str
    ) -> dict[str, Any]:
        return self._request(
            "GET",
            self.settings.yanyue_base_url,
            f"/api/internal/agent/resources/{resource_id}/availability",
            self.settings.yanyue_internal_key,
            username,
            params={"date": date},
        )

    def my_reservations(self, username: str) -> list[dict[str, Any]]:
        return self._request(
            "GET",
            self.settings.yanyue_base_url,
            "/api/internal/agent/reservations/mine",
            self.settings.yanyue_internal_key,
            username,
        )

    def create_reservation(self, username: str, payload: dict[str, Any]) -> dict[str, Any]:
        return self._request(
            "POST",
            self.settings.yanyue_base_url,
            "/api/internal/agent/reservations",
            self.settings.yanyue_internal_key,
            username,
            json=payload,
            approved=True,
        )

    def cancel_reservation(self, username: str, reservation_id: int) -> None:
        self._request(
            "DELETE",
            self.settings.yanyue_base_url,
            f"/api/internal/agent/reservations/{reservation_id}",
            self.settings.yanyue_internal_key,
            username,
            approved=True,
        )

    def check_in(self, username: str, reservation_id: int) -> dict[str, Any]:
        return self._request(
            "POST",
            self.settings.yanyue_base_url,
            f"/api/internal/agent/reservations/{reservation_id}/check-in",
            self.settings.yanyue_internal_key,
            username,
            approved=True,
        )

