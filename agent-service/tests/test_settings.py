from __future__ import annotations

import pytest

from app.settings import Settings


def test_production_rejects_sqlite_and_default_secrets(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.setenv("CHECKPOINT_BACKEND", "sqlite")
    with pytest.raises(ValueError):
        Settings.from_env()


def test_development_accepts_sqlite(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("APP_ENV", "development")
    monkeypatch.setenv("CHECKPOINT_BACKEND", "sqlite")
    settings = Settings.from_env()
    assert settings.checkpoint_backend == "sqlite"


def test_production_agentops_requires_service_identity(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("APP_ENV", "production")
    monkeypatch.setenv("AGENT_SERVICE_KEY", "production-service-key")
    monkeypatch.setenv("DOCPILOT_INTERNAL_KEY", "production-internal-key")
    monkeypatch.setenv("DOCPILOT_IDENTITY_TOKEN_SECRET", "docpilot-identity-secret")
    monkeypatch.setenv("CHECKPOINT_BACKEND", "postgres")
    monkeypatch.setenv("CHECKPOINT_DSN", "postgresql://agent:secret@postgres/agent")
    monkeypatch.setenv("AGENTOPS_ENABLED", "true")
    monkeypatch.setenv("AGENTOPS_API_KEY", "platform-key")
    monkeypatch.setenv("AGENTOPS_GOVERNANCE_ENABLED", "true")
    monkeypatch.delenv("AGENTOPS_IDENTITY_TOKEN", raising=False)
    monkeypatch.delenv("AGENTOPS_IDENTITY_TOKEN_SECRET", raising=False)

    with pytest.raises(ValueError, match="identity token or signing secret"):
        Settings.from_env()

    monkeypatch.setenv("AGENTOPS_IDENTITY_TOKEN_SECRET", "identity-secret")
    assert Settings.from_env().agentops_identity_subject == "docpilot-agent"
