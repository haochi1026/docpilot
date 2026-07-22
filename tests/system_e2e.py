"""Black-box acceptance test for DocPilot + AgentOps Hub.

Run after ``docker compose -f docker-compose.system-e2e.yml up -d --build --wait``.
Only the Python standard library is used so CI does not need a test virtualenv.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
import uuid
from typing import Any
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


DOCPILOT = os.getenv("DOCPILOT_E2E_URL", "http://127.0.0.1:28081").rstrip("/")
AGENTOPS = os.getenv("AGENTOPS_E2E_URL", "http://127.0.0.1:28100").rstrip("/")
PLATFORM_KEY = "system-e2e-platform-key-with-sufficient-entropy"
IDENTITY_SECRET = "system-e2e-identity-secret-with-sufficient-entropy"
E2E_CODE = "E2E-AGENT-7319"


def _json_request(
    method: str,
    url: str,
    payload: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
    timeout: float = 30,
) -> Any:
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    request_headers = {"Accept": "application/json", **(headers or {})}
    if body is not None:
        request_headers["Content-Type"] = "application/json; charset=utf-8"
    request = Request(url, data=body, headers=request_headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read()
            return json.loads(raw) if raw else None
    except HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise AssertionError(f"{method} {url} -> {exc.code}: {detail}") from exc


def _wait_json(
    url: str,
    predicate,
    timeout: float = 180,
    headers: dict[str, str] | None = None,
) -> Any:
    deadline = time.monotonic() + timeout
    last: Any = None
    while time.monotonic() < deadline:
        try:
            last = _json_request("GET", url, headers=headers, timeout=10)
            if predicate(last):
                return last
        except (OSError, AssertionError):
            pass
        time.sleep(1)
    raise AssertionError(f"timed out waiting for {url}; last={last!r}")


def _upload(kb_id: int, token: str, filename: str, content: bytes) -> dict[str, Any]:
    boundary = "----docpilot-system-e2e-" + uuid.uuid4().hex
    body = b"".join(
        [
            f"--{boundary}\r\n".encode(),
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'.encode(),
            b"Content-Type: text/markdown\r\n\r\n",
            content,
            b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
    )
    request = Request(
        f"{DOCPILOT}/api/documents?{urlencode({'kbId': kb_id})}",
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Accept": "application/json",
        },
        method="POST",
    )
    try:
        with urlopen(request, timeout=30) as response:
            return json.loads(response.read())
    except HTTPError as exc:
        raise AssertionError(
            f"upload {filename} -> {exc.code}: {exc.read().decode(errors='replace')}"
        ) from exc


def _sse(path: str, payload: dict[str, Any], token: str) -> list[tuple[str, Any]]:
    request = Request(
        DOCPILOT + path,
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json; charset=utf-8",
            "Accept": "text/event-stream",
        },
        method="POST",
    )
    events: list[tuple[str, Any]] = []
    try:
        with urlopen(request, timeout=190) as response:
            event_name = "message"
            data_lines: list[str] = []
            for raw in response:
                line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                if not line:
                    if data_lines:
                        value: Any = "\n".join(data_lines)
                        try:
                            value = json.loads(value)
                        except json.JSONDecodeError:
                            pass
                        events.append((event_name, value))
                    event_name, data_lines = "message", []
                elif line.startswith("event:"):
                    event_name = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].lstrip())
            if data_lines:
                value = "\n".join(data_lines)
                try:
                    value = json.loads(value)
                except json.JSONDecodeError:
                    pass
                events.append((event_name, value))
    except HTTPError as exc:
        raise AssertionError(
            f"POST {path} -> {exc.code}: {exc.read().decode(errors='replace')}"
        ) from exc
    errors = [value for name, value in events if name == "error"]
    if errors:
        raise AssertionError(f"SSE returned errors: {errors}")
    return events


def _agentops_headers(role: str = "ADMIN") -> dict[str, str]:
    now = int(time.time())
    payload = {
        "iss": "docpilot-agent",
        "aud": "agentops-hub",
        "kid": "v1",
        "jti": uuid.uuid4().hex,
        "sub": "system-e2e",
        "tenant": "default",
        "role": role,
        "iat": now,
        "nbf": now - 1,
        "exp": now + 300,
    }
    encoded = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":")).encode()
    ).decode().rstrip("=")
    signature = base64.urlsafe_b64encode(
        hmac.new(IDENTITY_SECRET.encode(), encoded.encode(), hashlib.sha256).digest()
    ).decode().rstrip("=")
    return {
        "X-Platform-Key": PLATFORM_KEY,
        "X-Identity-Token": f"{encoded}.{signature}",
    }


def _ndjson(url: str, payload: dict[str, Any], headers: dict[str, str]) -> list[dict[str, Any]]:
    request = Request(
        url,
        data=json.dumps(payload, ensure_ascii=False).encode(),
        headers={**headers, "Content-Type": "application/json", "Accept": "application/x-ndjson"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=190) as response:
            return [json.loads(line) for line in response if line.strip()]
    except HTTPError as exc:
        raise AssertionError(
            f"POST {url} -> {exc.code}: {exc.read().decode(errors='replace')}"
        ) from exc


def _event_value(events: list[tuple[str, Any]], name: str) -> Any:
    matches = [value for event, value in events if event == name]
    return matches[-1] if matches else None


def _create_eval_run(headers: dict[str, str], dataset_id: str, version: str) -> str:
    run = _json_request(
        "POST",
        f"{AGENTOPS}/api/v1/evaluation-runs",
        {
            "dataset_id": dataset_id,
            "target_url": "http://agent-service:8090/v1/agent/evaluate",
            "target_auth_env": "DOCPILOT_AGENT_SERVICE_KEY",
            "app_version": version,
            "model": "deterministic-extractive-path",
            "prompt_version": "system-e2e-v1",
            "artifact_manifest": {"suite": "cross-system-e2e"},
        },
        headers,
    )
    return str(run["id"])


def _wait_run(headers: dict[str, str], run_id: str) -> dict[str, Any]:
    deadline = time.monotonic() + 180
    last: dict[str, Any] = {}
    while time.monotonic() < deadline:
        last = _json_request(
            "GET", f"{AGENTOPS}/api/v1/evaluation-runs/{run_id}", headers=headers
        )
        if last["status"] in {"COMPLETED", "PARTIAL", "ERROR"}:
            break
        time.sleep(1)
    assert last.get("status") == "COMPLETED", last
    assert last.get("summary_json", {}).get("pass_rate") == 1.0, last
    return last


def main() -> None:
    _wait_json(f"{DOCPILOT}/actuator/health", lambda value: value.get("status") == "UP")
    _wait_json(f"{AGENTOPS}/health/ready", lambda value: value.get("status") == "UP")

    login = _json_request(
        "POST", f"{DOCPILOT}/api/auth/login", {"username": "admin", "password": "123456"}
    )
    token, user_id = login["token"], int(login["userId"])
    auth = {"Authorization": f"Bearer {token}"}
    kb = _json_request(
        "POST",
        f"{DOCPILOT}/api/kbs",
        {"name": "system-e2e-" + uuid.uuid4().hex[:8], "description": "cross-system acceptance"},
        auth,
    )
    kb_id = int(kb["id"])

    document = _upload(
        kb_id,
        token,
        "emergency-handbook.md",
        (
            "# Emergency response handbook\n\n"
            "The emergency response code is E2E-AGENT-7319. "
            "Operators use this code when opening a verified incident record.\n"
        ).encode(),
    )
    document_id = int(document["id"])
    _wait_json(
        f"{DOCPILOT}/api/documents/{document_id}",
        lambda value: value.get("status") == "SUCCESS",
        timeout=90,
        headers=auth,
    )

    chat = _sse(
        "/api/chat/stream",
        {"kbId": kb_id, "question": "What is the emergency response code?"},
        token,
    )
    answer = str(_event_value(chat, "replace") or "")
    sources = _event_value(chat, "sources") or []
    assert E2E_CODE in answer, chat
    assert sources and any(int(item.get("documentId", 0)) == document_id for item in sources), sources
    assert _event_value(chat, "done") is not None, chat

    ops_headers = _agentops_headers()
    identity = _json_request("GET", f"{AGENTOPS}/api/v1/auth/whoami", headers=ops_headers)
    assert identity == {
        "actor_id": "system-e2e",
        "tenant_id": "default",
        "role": "ADMIN",
        "auth_mode": "hmac",
    }, identity
    traces = _json_request("GET", f"{AGENTOPS}/api/v1/traces?limit=20", headers=ops_headers)
    matching = [item for item in traces if item.get("service_name") == "docpilot-agent"]
    assert matching, traces
    assert any(
        span.get("name") == "search_knowledge_base"
        for trace in matching
        for span in trace.get("spans", [])
    ), matching

    dataset = _json_request(
        "POST",
        f"{AGENTOPS}/api/v1/datasets",
        {"name": "docpilot-system-e2e", "description": "real cross-service baseline"},
        ops_headers,
    )
    dataset_id = str(dataset["id"])
    _json_request(
        "POST",
        f"{AGENTOPS}/api/v1/datasets/{dataset_id}/cases",
        {
            "name": "retrieves emergency response code",
            "input": {
                "thread_id": "eval-e2e-base",
                "username": "admin",
                "user_id": user_id,
                "role": "ADMIN",
                "kb_id": kb_id,
                "message": "What is the emergency response code?",
            },
            "expected": {"status": "SUCCESS", "answer_contains": [E2E_CODE]},
            "evaluator_config": {
                "answer_contains": [E2E_CODE],
                "min_sources": 1,
                "pass_threshold": 1.0,
            },
            "tags": ["retrieval", "cross-system"],
        },
        ops_headers,
    )
    _json_request("POST", f"{AGENTOPS}/api/v1/datasets/{dataset_id}/freeze", headers=ops_headers)
    baseline_id = _create_eval_run(ops_headers, dataset_id, "baseline")
    candidate_id = _create_eval_run(ops_headers, dataset_id, "candidate")
    baseline = _wait_run(ops_headers, baseline_id)
    candidate = _wait_run(ops_headers, candidate_id)
    comparison = _json_request(
        "GET",
        f"{AGENTOPS}/api/v1/evaluation-comparisons?{urlencode({'baseline_run_id': baseline_id, 'candidate_run_id': candidate_id})}",
        headers=ops_headers,
    )
    assert comparison["score_delta"] == 0.0 and not comparison["regressions"], comparison
    summary = _json_request(
        "GET", f"{AGENTOPS}/api/v1/dashboard/summary", headers=ops_headers
    )
    assert summary["tenant_id"] == "default", summary
    assert summary["counts"]["traces"] >= 1, summary
    assert summary["counts"]["evaluation_runs"] >= 2, summary
    assert summary["counts"]["datasets"] >= 1, summary
    audit_events = _json_request(
        "GET", f"{AGENTOPS}/api/v1/audit-events?limit=20", headers=ops_headers
    )
    assert any(item.get("action") == "evaluation.run.create" for item in audit_events), audit_events

    diagnostic = _ndjson(
        f"{AGENTOPS}/api/v1/evalops/chat/stream",
        {
            "thread_id": "system-e2e-comparison",
            "message": f"compare baseline {baseline_id} and candidate {candidate_id}",
        },
        ops_headers,
    )
    assert any(item.get("type") == "done" for item in diagnostic), diagnostic
    assert not any(item.get("type") == "error" for item in diagnostic), diagnostic

    # A one-byte document deterministically fails extraction. The failure then
    # traverses DocPilot's persistent business approval and LangGraph resume path.
    failed = _upload(kb_id, token, "failed.md", b"x")
    failed_id = int(failed["id"])
    _wait_json(
        f"{DOCPILOT}/api/documents/{failed_id}",
        lambda value: value.get("status") == "FAILED",
        timeout=90,
        headers=auth,
    )
    interrupted = _sse(
        "/api/chat/stream",
        {"kbId": kb_id, "question": f"重新解析文档 {failed_id}"},
        token,
    )
    approval = _event_value(interrupted, "approval")
    conversation_id = int(_event_value(interrupted, "conversation"))
    assert isinstance(approval, dict) and approval.get("approvalId"), interrupted
    resumed = _sse(
        "/api/chat/resume",
        {
            "kbId": kb_id,
            "conversationId": conversation_id,
            "approvalId": approval["approvalId"],
            "decision": "approve",
        },
        token,
    )
    assert _event_value(resumed, "done") is not None, resumed
    pending = _json_request(
        "GET",
        f"{DOCPILOT}/api/chat/approvals/pending?{urlencode({'conversationId': conversation_id})}",
        headers=auth,
    )
    assert pending == {}, pending

    print(
        json.dumps(
            {
                "status": "PASS",
                "document_id": document_id,
                "trace_id": matching[0]["id"],
                "baseline_run_id": baseline_id,
                "candidate_run_id": candidate_id,
                "baseline": baseline["summary_json"],
                "candidate": candidate["summary_json"],
                "hitl_approval_id": approval["approvalId"],
            },
            ensure_ascii=False,
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
