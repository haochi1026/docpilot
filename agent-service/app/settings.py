from __future__ import annotations

import os
from dataclasses import dataclass


def _bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    ollama_base_url: str
    ollama_model: str
    docpilot_base_url: str
    docpilot_internal_key: str
    yanyue_enabled: bool
    yanyue_base_url: str
    yanyue_internal_key: str
    checkpoint_path: str
    request_timeout_seconds: float

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            ollama_base_url=os.getenv("OLLAMA_BASE_URL", "http://host.docker.internal:11434"),
            ollama_model=os.getenv("OLLAMA_MODEL", "qwen3.5:2b"),
            docpilot_base_url=os.getenv("DOCPILOT_BASE_URL", "http://server:8080"),
            docpilot_internal_key=os.getenv(
                "DOCPILOT_INTERNAL_KEY", "change-this-agent-internal-key"
            ),
            yanyue_enabled=_bool("YANYUE_ENABLED", True),
            yanyue_base_url=os.getenv(
                "YANYUE_BASE_URL", "http://host.docker.internal:18080"
            ),
            yanyue_internal_key=os.getenv(
                "YANYUE_INTERNAL_KEY", "change-this-yanyue-agent-key"
            ),
            checkpoint_path=os.getenv("CHECKPOINT_PATH", "/data/checkpoints.sqlite"),
            request_timeout_seconds=float(os.getenv("TOOL_TIMEOUT_SECONDS", "10")),
        )

