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
