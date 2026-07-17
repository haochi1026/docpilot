from __future__ import annotations

import os
from dataclasses import dataclass


def _bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _int(name: str, default: int, minimum: int = 0) -> int:
    value = int(os.getenv(name, str(default)))
    if value < minimum:
        raise ValueError(f"{name} must be >= {minimum}")
    return value


def _float(name: str, default: float, minimum: float = 0.0) -> float:
    value = float(os.getenv(name, str(default)))
    if value < minimum:
        raise ValueError(f"{name} must be >= {minimum}")
    return value


@dataclass(frozen=True)
class Settings:
    environment: str
    agent_service_key: str
    ollama_base_url: str
    ollama_model: str
    docpilot_base_url: str
    docpilot_internal_key: str
    yanyue_enabled: bool
    yanyue_base_url: str
    yanyue_internal_key: str
    checkpoint_backend: str
    checkpoint_path: str
    checkpoint_dsn: str
    request_timeout_seconds: float
    gateway_max_retries: int
    gateway_backoff_seconds: float
    circuit_failure_threshold: int
    circuit_recovery_seconds: float
    model_run_limit: int
    tool_run_limit: int
    agentops_enabled: bool
    agentops_base_url: str
    agentops_api_key: str
    agentops_timeout_seconds: float

    @property
    def production(self) -> bool:
        return self.environment.lower() in {"prod", "production"}

    def validate(self) -> None:
        if self.checkpoint_backend not in {"sqlite", "postgres"}:
            raise ValueError("CHECKPOINT_BACKEND must be sqlite or postgres")
        if self.checkpoint_backend == "postgres" and not self.checkpoint_dsn:
            raise ValueError("CHECKPOINT_DSN is required for postgres checkpoints")
        if self.production:
            weak = {
                "",
                "change-this-agent-service-key",
                "docpilot-agent-local-key-change-me",
                "change-this-agent-internal-key",
            }
            if self.agent_service_key in weak:
                raise ValueError("AGENT_SERVICE_KEY must be changed in production")
            if self.docpilot_internal_key in weak:
                raise ValueError("DOCPILOT_INTERNAL_KEY must be changed in production")
            if self.checkpoint_backend != "postgres":
                raise ValueError("production mode requires postgres checkpoints")
            if self.agentops_enabled and not self.agentops_api_key:
                raise ValueError("AGENTOPS_API_KEY is required when AgentOps is enabled")

    @classmethod
    def from_env(cls) -> "Settings":
        settings = cls(
            environment=os.getenv("APP_ENV", "development"),
            agent_service_key=os.getenv(
                "AGENT_SERVICE_KEY", "change-this-agent-service-key"
            ),
            ollama_base_url=os.getenv(
                "OLLAMA_BASE_URL", "http://host.docker.internal:11434"
            ),
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
            checkpoint_backend=os.getenv("CHECKPOINT_BACKEND", "sqlite").lower(),
            checkpoint_path=os.getenv(
                "CHECKPOINT_PATH", "/data/checkpoints.sqlite"
            ),
            checkpoint_dsn=os.getenv("CHECKPOINT_DSN", ""),
            request_timeout_seconds=_float("TOOL_TIMEOUT_SECONDS", 10, 0.1),
            gateway_max_retries=_int("TOOL_MAX_RETRIES", 2, 0),
            gateway_backoff_seconds=_float("TOOL_RETRY_BACKOFF_SECONDS", 0.2, 0),
            circuit_failure_threshold=_int("TOOL_CIRCUIT_FAILURES", 5, 1),
            circuit_recovery_seconds=_float("TOOL_CIRCUIT_RECOVERY_SECONDS", 30, 1),
            model_run_limit=_int("AGENT_MODEL_RUN_LIMIT", 8, 1),
            tool_run_limit=_int("AGENT_TOOL_RUN_LIMIT", 12, 1),
            agentops_enabled=_bool("AGENTOPS_ENABLED", False),
            agentops_base_url=os.getenv(
                "AGENTOPS_BASE_URL", "http://host.docker.internal:18100"
            ),
            agentops_api_key=os.getenv("AGENTOPS_API_KEY", ""),
            agentops_timeout_seconds=_float("AGENTOPS_TIMEOUT_SECONDS", 2, 0.1),
        )
        settings.validate()
        return settings
