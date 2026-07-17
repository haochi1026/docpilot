from __future__ import annotations

import pytest
from fastapi import HTTPException

from app.security import ServiceKeyGuard

from test_gateway import settings


def test_service_key_uses_constant_time_comparison() -> None:
    guard = ServiceKeyGuard(settings())
    assert guard("service-key") is None
    with pytest.raises(HTTPException) as exc:
        guard("wrong-key")
    assert exc.value.status_code == 401
