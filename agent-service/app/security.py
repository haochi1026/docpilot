from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, status

from .settings import Settings


class ServiceKeyGuard:
    """Authenticate Java-to-Agent calls without exposing the key to the model."""

    def __init__(self, settings: Settings) -> None:
        self.expected = settings.agent_service_key.encode("utf-8")

    def __call__(self, x_agent_service_key: str = Header(default="")) -> None:
        supplied = x_agent_service_key.encode("utf-8")
        if not supplied or not hmac.compare_digest(self.expected, supplied):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid agent service credential",
            )
