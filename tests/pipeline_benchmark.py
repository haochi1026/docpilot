#!/usr/bin/env python3
"""Upload/parse/SSE benchmark for DocPilot.

The script uses only the Python standard library. It can run against either the
local-thread-pool or RocketMQ compose profile. With --ollama it temporarily
switches the persisted model settings to the local OpenAI-compatible Ollama
endpoint and restores the previous settings before exiting.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import json
import math
import mimetypes
import statistics
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any


class Api:
    def __init__(self, base_url: str, timeout: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.token: str | None = None

    def request(
        self,
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        auth: bool = True,
        timeout: float | None = None,
    ) -> dict[str, Any]:
        headers = {"Accept": "application/json"}
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        if auth and self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(
            self.base_url + path, data=data, headers=headers, method=method
        )
        return self._open(request, timeout or self.timeout)

    def upload(
        self,
        kb_id: int,
        filename: str,
        content: bytes,
        timeout: float | None = None,
    ) -> dict[str, Any]:
        boundary = "----DocPilotBenchmark" + uuid.uuid4().hex
        content_type = mimetypes.guess_type(filename)[0] or "application/octet-stream"
        prefix = (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
            f"Content-Type: {content_type}\r\n\r\n"
        ).encode("utf-8")
        suffix = f"\r\n--{boundary}--\r\n".encode("ascii")
        path = "/api/documents?" + urllib.parse.urlencode({"kbId": kb_id})
        headers = {
            "Accept": "application/json",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Content-Length": str(len(prefix) + len(content) + len(suffix)),
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = urllib.request.Request(
            self.base_url + path,
            data=prefix + content + suffix,
            headers=headers,
            method="POST",
        )
        return self._open(request, timeout or self.timeout)

    def _open(self, request: urllib.request.Request, timeout: float) -> dict[str, Any]:
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read()
                status = response.status
        except urllib.error.HTTPError as exc:
            raw = exc.read()
            status = exc.code
        except Exception as exc:
            return {
                "status": 0,
                "elapsedMs": round((time.perf_counter() - started) * 1000, 2),
                "data": None,
                "raw": str(exc),
            }
        elapsed = round((time.perf_counter() - started) * 1000, 2)
        text = raw.decode("utf-8", errors="replace") if raw else ""
        try:
            parsed = json.loads(text) if text else None
        except json.JSONDecodeError:
            parsed = None
        return {"status": status, "elapsedMs": elapsed, "data": parsed, "raw": text}

    def login(self, username: str, password: str) -> dict[str, Any]:
        response = self.request(
            "POST",
            "/api/auth/login",
            {"username": username, "password": password},
            auth=False,
        )
        if response["status"] != 200 or not response["data"]:
            raise RuntimeError(f"login failed: {response}")
        self.token = response["data"]["token"]
        return response["data"]

    def sse_chat(self, kb_id: int, question: str, timeout: float = 150.0) -> dict[str, Any]:
        payload = json.dumps(
            {"kbId": kb_id, "question": question}, ensure_ascii=False
        ).encode("utf-8")
        headers = {
            "Accept": "text/event-stream",
            "Content-Type": "application/json; charset=utf-8",
            "Authorization": f"Bearer {self.token}",
        }
        request = urllib.request.Request(
            self.base_url + "/api/chat/stream",
            data=payload,
            headers=headers,
            method="POST",
        )
        started = time.perf_counter()
        first_token_ms: float | None = None
        events: list[str] = []
        token_chars = 0
        current_event = "message"
        status = 0
        error_text = None
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                status = response.status
                while True:
                    raw_line = response.readline()
                    if not raw_line:
                        break
                    line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                    if line.startswith("event:"):
                        current_event = line[6:].strip()
                    elif line.startswith("data:"):
                        data = line[5:].lstrip()
                        events.append(current_event)
                        if current_event == "token":
                            if first_token_ms is None:
                                first_token_ms = (time.perf_counter() - started) * 1000
                            token_chars += len(data)
                        if current_event in ("done", "error"):
                            if current_event == "error":
                                error_text = data
                            break
                    elif not line:
                        current_event = "message"
        except urllib.error.HTTPError as exc:
            status = exc.code
            error_text = exc.read().decode("utf-8", errors="replace")
        except Exception as exc:
            error_text = str(exc)
        total_ms = (time.perf_counter() - started) * 1000
        return {
            "status": status,
            "firstTokenMs": round(first_token_ms, 2) if first_token_ms is not None else None,
            "totalMs": round(total_ms, 2),
            "tokenChars": token_chars,
            "events": events,
            "error": error_text,
            "passed": status == 200 and "done" in events and error_text is None,
        }


def nearest_rank(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(percentile * len(ordered)) - 1)
    return round(ordered[index], 2)


def summary(values: list[float]) -> dict[str, Any]:
    return {
        "count": len(values),
        "minMs": round(min(values), 2) if values else None,
        "meanMs": round(statistics.fmean(values), 2) if values else None,
        "p50Ms": nearest_rank(values, 0.50),
        "p95Ms": nearest_rank(values, 0.95),
        "p99Ms": nearest_rank(values, 0.99),
        "maxMs": round(max(values), 2) if values else None,
    }


def make_text(run_id: str, index: int, size_kb: int) -> bytes:
    fact = (
        f"DocPilot benchmark {run_id}, document {index}. "
        f"The verification code for document {index} is DP-{run_id}-{index}. "
        "This file verifies document upload, asynchronous parsing, chunking, retrieval, "
        "citation construction and streaming answer generation.\n"
    )
    repeat = max(1, size_kb * 1024 // len(fact.encode("utf-8")))
    return (fact * repeat).encode("utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18081")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--uploads", type=int, default=8)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument("--size-kb", type=int, default=64)
    parser.add_argument("--parse-timeout", type=float, default=240.0)
    parser.add_argument("--chat-concurrency", type=int, default=1)
    parser.add_argument("--ollama", action="store_true")
    parser.add_argument("--ai-model", default="qwen3.5:2b")
    parser.add_argument("--embedding-model", default="qwen3-embedding:0.6b")
    parser.add_argument("--ollama-base-url", default="http://host.docker.internal:11434/v1")
    parser.add_argument("--keep-documents", action="store_true")
    parser.add_argument("--keep-settings", action="store_true")
    parser.add_argument("--output")
    args = parser.parse_args()

    if args.uploads < 1 or args.uploads > 100:
        parser.error("--uploads must be between 1 and 100")
    if args.workers < 1 or args.workers > args.uploads:
        parser.error("--workers must be between 1 and --uploads")
    if args.chat_concurrency < 0 or args.chat_concurrency > 3:
        parser.error("--chat-concurrency must be between 0 and 3")

    api = Api(args.base_url)
    health = api.request("GET", "/actuator/health", auth=False)
    if health["status"] != 200:
        raise RuntimeError(f"service is not healthy: {health}")
    identity = api.login(args.username, args.password)
    capabilities = api.request("GET", "/api/system/capabilities")
    kb_response = api.request("GET", "/api/kbs")
    if kb_response["status"] != 200 or not kb_response["data"]:
        raise RuntimeError(f"cannot list knowledge bases: {kb_response}")
    knowledge_base = kb_response["data"][0]
    kb_id = int(knowledge_base["id"])
    run_id = dt.datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
    report: dict[str, Any] = {
        "runId": run_id,
        "startedAt": dt.datetime.now().astimezone().isoformat(),
        "baseUrl": args.base_url,
        "user": {"userId": identity["userId"], "role": identity["role"]},
        "knowledgeBase": {"id": kb_id, "name": knowledge_base.get("name")},
        "capabilitiesBefore": capabilities.get("data"),
        "parameters": {
            "uploads": args.uploads,
            "workers": args.workers,
            "sizeKb": args.size_kb,
            "chatConcurrency": args.chat_concurrency,
            "ollama": args.ollama,
        },
    }
    original_settings: dict[str, Any] | None = None
    uploaded_ids: list[int] = []
    failures: list[str] = []

    try:
        if args.ollama:
            current = api.request("GET", "/api/system/settings")
            if current["status"] != 200:
                raise RuntimeError(f"cannot read model settings: {current}")
            original_settings = current["data"]
            candidate = {
                "aiMode": "openai",
                "aiBaseUrl": args.ollama_base_url,
                "aiModel": args.ai_model,
                "embeddingMode": "openai",
                "embeddingBaseUrl": args.ollama_base_url,
                "embeddingModel": args.embedding_model,
            }
            connection = api.request(
                "POST", "/api/system/test-models", candidate, timeout=180.0
            )
            report["ollamaConnectionTest"] = connection
            if connection["status"] != 200:
                raise RuntimeError(f"Ollama model connection failed: {connection}")
            updated = api.request("PUT", "/api/system/settings", candidate)
            if updated["status"] != 200:
                raise RuntimeError(f"cannot update model settings: {updated}")
            report["activeSettings"] = updated["data"]

        upload_gate = threading.Event()

        def upload_one(index: int) -> dict[str, Any]:
            content = make_text(run_id, index, args.size_kb)
            upload_gate.wait(timeout=20)
            response = api.upload(
                kb_id,
                f"benchmark-{run_id}-{index}.txt",
                content,
                timeout=max(30.0, args.size_kb / 128.0),
            )
            response["index"] = index
            response["acceptedAtMonotonic"] = time.perf_counter()
            return response

        upload_started = time.perf_counter()
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(upload_one, index) for index in range(args.uploads)]
            upload_gate.set()
            upload_responses = [future.result() for future in futures]
        upload_wall_ms = (time.perf_counter() - upload_started) * 1000
        for response in upload_responses:
            if response["status"] == 200 and response["data"]:
                uploaded_ids.append(int(response["data"]["id"]))
        upload_passed = len(uploaded_ids) == args.uploads
        if not upload_passed:
            failures.append("concurrentUpload")
        report["upload"] = {
            "passed": upload_passed,
            "statusCounts": {
                str(status): sum(1 for r in upload_responses if r["status"] == status)
                for status in sorted({r["status"] for r in upload_responses})
            },
            "latency": summary([float(r["elapsedMs"]) for r in upload_responses]),
            "wallMs": round(upload_wall_ms, 2),
            "throughputPerSecond": round(args.uploads / (upload_wall_ms / 1000), 2),
            "documentIds": uploaded_ids,
        }

        accepted_times = {
            int(r["data"]["id"]): float(r["acceptedAtMonotonic"])
            for r in upload_responses
            if r["status"] == 200 and r["data"]
        }
        terminal: dict[int, dict[str, Any]] = {}
        deadline = time.monotonic() + args.parse_timeout
        while uploaded_ids and time.monotonic() < deadline:
            listing = api.request("GET", f"/api/documents?kbId={kb_id}")
            if listing["status"] != 200:
                time.sleep(0.5)
                continue
            now = time.perf_counter()
            by_id = {int(item["id"]): item for item in listing["data"]}
            for document_id in uploaded_ids:
                item = by_id.get(document_id)
                if item and item.get("status") in ("SUCCESS", "FAILED"):
                    terminal.setdefault(
                        document_id,
                        {
                            "status": item.get("status"),
                            "errorMessage": item.get("errorMessage"),
                            "completionMs": round(
                                (now - accepted_times[document_id]) * 1000, 2
                            ),
                        },
                    )
            if len(terminal) == len(uploaded_ids):
                break
            time.sleep(0.5)

        parse_passed = (
            len(terminal) == len(uploaded_ids)
            and all(item["status"] == "SUCCESS" for item in terminal.values())
        )
        if not parse_passed:
            failures.append("asynchronousParse")
        report["parse"] = {
            "passed": parse_passed,
            "terminalCount": len(terminal),
            "successCount": sum(1 for item in terminal.values() if item["status"] == "SUCCESS"),
            "failedCount": sum(1 for item in terminal.values() if item["status"] == "FAILED"),
            "completionLatency": summary(
                [float(item["completionMs"]) for item in terminal.values()]
            ),
            "documents": terminal,
        }

        if args.chat_concurrency > 0 and parse_passed:
            chat_barrier = threading.Barrier(args.chat_concurrency)

            def chat_one(index: int) -> dict[str, Any]:
                try:
                    chat_barrier.wait(timeout=20)
                except threading.BrokenBarrierError:
                    pass
                return api.sse_chat(
                    kb_id,
                    f"What is the verification code for document {index % args.uploads}?",
                )

            with concurrent.futures.ThreadPoolExecutor(
                max_workers=args.chat_concurrency
            ) as pool:
                chat_results = list(pool.map(chat_one, range(args.chat_concurrency)))
            chat_passed = all(item["passed"] for item in chat_results)
            if not chat_passed:
                failures.append("sseChat")
            report["chat"] = {
                "passed": chat_passed,
                "firstTokenLatency": summary(
                    [
                        float(item["firstTokenMs"])
                        for item in chat_results
                        if item["firstTokenMs"] is not None
                    ]
                ),
                "totalLatency": summary(
                    [float(item["totalMs"]) for item in chat_results]
                ),
                "results": chat_results,
            }
        else:
            report["chat"] = {"skipped": True, "reason": "disabled or parsing failed"}
    finally:
        if uploaded_ids and not args.keep_documents:
            cleanup = []
            for document_id in uploaded_ids:
                cleanup.append(
                    {
                        "documentId": document_id,
                        "status": api.request(
                            "DELETE", f"/api/documents/{document_id}"
                        )["status"],
                    }
                )
            report["documentCleanup"] = cleanup
        if original_settings is not None and not args.keep_settings:
            restored = api.request("PUT", "/api/system/settings", original_settings)
            report["settingsRestore"] = {
                "status": restored["status"],
                "restored": restored["status"] == 200,
            }

    report["finishedAt"] = dt.datetime.now().astimezone().isoformat()
    report["passed"] = not failures
    report["failedTests"] = failures
    default_output = (
        Path(__file__).resolve().parent
        / "results"
        / f"docpilot_pipeline_{run_id}.json"
    )
    output = Path(args.output) if args.output else default_output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"\nreport: {output}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
