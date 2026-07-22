#!/usr/bin/env python3
"""Prepare and verify the AgentOps expired-lease restart recovery drill."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import time
import urllib.request
import uuid
from typing import Any


def identity_token() -> str:
    now = int(time.time())
    payload = {
        "sub": "restart-recovery-drill",
        "tenant": "default",
        "role": "ADMIN",
        "iss": "docpilot-agent",
        "aud": "agentops-hub",
        "kid": "v1",
        "jti": uuid.uuid4().hex,
        "iat": now,
        "nbf": now - 1,
        "exp": now + 600,
    }
    encoded = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
    ).decode().rstrip("=")
    signature = base64.urlsafe_b64encode(
        hmac.new(
            b"system-e2e-identity-secret-with-sufficient-entropy",
            encoded.encode(),
            hashlib.sha256,
        ).digest()
    ).decode().rstrip("=")
    return f"{encoded}.{signature}"


def request(base_url: str, method: str, path: str, body: dict[str, Any] | None = None) -> Any:
    data = None if body is None else json.dumps(body).encode()
    headers = {
        "X-Platform-Key": "system-e2e-platform-key-with-sufficient-entropy",
        "X-Identity-Token": identity_token(),
        "Accept": "application/json",
    }
    if data is not None:
        headers["Content-Type"] = "application/json"
    with urllib.request.urlopen(
        urllib.request.Request(base_url + path, data=data, headers=headers, method=method),
        timeout=30,
    ) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


def prepare(base_url: str) -> str:
    suffix = uuid.uuid4().hex[:8]
    dataset = request(
        base_url,
        "POST",
        "/api/v1/datasets",
        {"name": f"restart-recovery-{suffix}", "description": "expired lease recovery drill"},
    )
    dataset_id = dataset["id"]
    request(
        base_url,
        "POST",
        f"/api/v1/datasets/{dataset_id}/cases",
        {
            "name": "single recoverable case",
            "input": {
                "thread_id": f"restart-drill-{suffix}",
                "username": "admin",
                "user_id": 2,
                "role": "ADMIN",
                "kb_id": 1,
                "message": "What is the emergency response code?",
            },
            "expected": {"status": "SUCCESS"},
            "evaluator_config": {"pass_threshold": 0.0},
            "tags": ["recovery-drill"],
        },
    )
    request(base_url, "POST", f"/api/v1/datasets/{dataset_id}/freeze")
    run = request(
        base_url,
        "POST",
        "/api/v1/evaluation-runs",
        {
            "dataset_id": dataset_id,
            "target_url": "http://agent-service:8090/v1/agent/evaluate",
            "target_auth_env": "DOCPILOT_AGENT_SERVICE_KEY",
            "app_version": "restart-recovery-drill",
            "model": "qwen3.5:2b",
            "prompt_version": "restart-recovery-v1",
        },
    )
    return str(run["id"])


def verify(base_url: str, run_id: str, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    last: dict[str, Any] | None = None
    while time.monotonic() < deadline:
        last = request(base_url, "GET", f"/api/v1/evaluation-runs/{run_id}")
        if last.get("status") in {"COMPLETED", "PARTIAL"}:
            print(json.dumps({"status": "PASS", "run_id": run_id, "run": last}, ensure_ascii=False))
            return
        if last.get("status") in {"ERROR", "DISPATCH_FAILED"}:
            raise RuntimeError(f"recovered run failed: {last}")
        time.sleep(1)
    raise TimeoutError(f"run did not finish after restart: {last}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["prepare", "verify"])
    parser.add_argument("--base-url", default="http://127.0.0.1:28100")
    parser.add_argument("--run-id")
    parser.add_argument("--timeout", type=float, default=180)
    args = parser.parse_args()
    if args.action == "prepare":
        print(prepare(args.base_url))
    else:
        if not args.run_id:
            parser.error("--run-id is required for verify")
        verify(args.base_url, args.run_id, args.timeout)


if __name__ == "__main__":
    main()
