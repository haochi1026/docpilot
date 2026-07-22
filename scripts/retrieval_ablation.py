"""Measure lexical/vector/hybrid/reranked retrieval against labeled questions."""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import time
import uuid
from pathlib import Path
from typing import Any
from urllib.error import HTTPError
from urllib.request import Request, urlopen


STRATEGIES = ("LEXICAL", "VECTOR", "HYBRID", "HYBRID_RERANK")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("kb_id", type=int)
    parser.add_argument(
        "--dataset",
        type=Path,
        default=Path(__file__).resolve().parent.parent
        / "tests"
        / "fixtures"
        / "retrieval_ablation.json",
    )
    parser.add_argument(
        "--base-url",
        default=os.getenv("DOCPILOT_BASE_URL", "http://127.0.0.1:18081"),
    )
    parser.add_argument(
        "--internal-key",
        default=os.getenv("AGENT_INTERNAL_KEY", "docpilot-agent-local-key-change-me"),
    )
    parser.add_argument("--username", default=os.getenv("DOCPILOT_USERNAME", "admin"))
    parser.add_argument(
        "--identity-secret",
        default=os.getenv("AGENT_INTERNAL_IDENTITY_SECRET", ""),
    )
    parser.add_argument("--top-k", type=int, default=4)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--markdown-output", type=Path)
    args = parser.parse_args()
    cases = json.loads(args.dataset.read_text(encoding="utf-8"))
    report = {
        "schema_version": 1,
        "kb_id": args.kb_id,
        "dataset": str(args.dataset),
        "top_k": args.top_k,
        "case_count": len(cases),
        "strategies": {
            strategy: evaluate(
                strategy,
                cases,
                args.base_url.rstrip("/"),
                args.internal_key,
                args.username,
                args.identity_secret,
                args.kb_id,
                args.top_k,
            )
            for strategy in STRATEGIES
        },
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    if args.markdown_output:
        args.markdown_output.parent.mkdir(parents=True, exist_ok=True)
        args.markdown_output.write_text(render_markdown(report), encoding="utf-8")
    print(rendered)


def render_markdown(report: dict[str, Any]) -> str:
    lines = [
        "# Retrieval ablation baseline",
        "",
        f"- Knowledge base: `{report['kb_id']}`",
        f"- Labeled questions: `{report['case_count']}` ({report['strategies']['LEXICAL']['positive_cases']} positive, {report['strategies']['LEXICAL']['negative_cases']} no-answer)",
        f"- Top K: `{report['top_k']}`",
        "",
        "| Strategy | Recall@K | MRR | No-answer accuracy |",
        "|---|---:|---:|---:|",
    ]
    for strategy, values in report["strategies"].items():
        lines.append(
            f"| {strategy} | {values['recall_at_k']:.4f} | {values['mrr']:.4f} | {values['no_answer_accuracy']:.4f} |"
        )
    lines.extend(
        [
            "",
            "The VECTOR row is intentionally a valid baseline for this local run: the Compose acceptance stack disables external vector indexing, so it returns no eligible vector candidates. HYBRID_RERANK is evaluated on the same threshold-qualified candidate set and exposes deterministic score components for trace inspection.",
            "",
        ]
    )
    return "\n".join(lines)


def evaluate(
    strategy: str,
    cases: list[dict[str, Any]],
    base_url: str,
    internal_key: str,
    username: str,
    identity_secret: str,
    kb_id: int,
    top_k: int,
) -> dict[str, Any]:
    positives = 0
    reciprocal_rank = 0.0
    positive_hits = 0
    negatives = 0
    negative_correct = 0
    details = []
    for case in cases:
        hits = search(
            base_url,
            internal_key,
            username,
            identity_secret,
            kb_id,
            str(case["question"]),
            top_k,
            strategy,
        )
        ranked_documents = [str(item.get("documentName", "")) for item in hits]
        expected = case.get("expected_document")
        rank = None
        if expected:
            positives += 1
            if expected in ranked_documents:
                rank = ranked_documents.index(expected) + 1
                positive_hits += 1
                reciprocal_rank += 1.0 / rank
        else:
            negatives += 1
            if not hits:
                negative_correct += 1
        details.append(
            {
                "name": case["name"],
                "expected_document": expected,
                "rank": rank,
                "returned_documents": ranked_documents,
                "scores": [round(float(item.get("score", 0)), 6) for item in hits],
            }
        )
    return {
        "positive_cases": positives,
        "recall_at_k": round(positive_hits / positives, 4) if positives else 0.0,
        "mrr": round(reciprocal_rank / positives, 4) if positives else 0.0,
        "negative_cases": negatives,
        "no_answer_accuracy": (
            round(negative_correct / negatives, 4) if negatives else 0.0
        ),
        "details": details,
    }


def search(
    base_url: str,
    internal_key: str,
    username: str,
    identity_secret: str,
    kb_id: int,
    question: str,
    top_k: int,
    strategy: str,
) -> list[dict[str, Any]]:
    headers = {
        "X-Agent-Key": internal_key,
        "X-Username": username,
        "Content-Type": "application/json; charset=utf-8",
        "Accept": "application/json",
    }
    if identity_secret:
        headers["X-Agent-Identity"] = signed_identity(username, identity_secret)
    request = Request(
        base_url + "/api/internal/agent/search",
        data=json.dumps(
            {
                "kbId": kb_id,
                "query": question,
                "topK": top_k,
                "strategy": strategy,
            },
            ensure_ascii=False,
        ).encode(),
        headers=headers,
        method="POST",
    )
    try:
        with urlopen(request, timeout=60) as response:
            return json.loads(response.read())
    except HTTPError as exc:
        raise RuntimeError(
            f"{strategy} search failed: {exc.code} {exc.read().decode(errors='replace')}"
        ) from exc


def signed_identity(username: str, secret: str) -> str:
    now = int(time.time())
    payload = {
        "iss": "docpilot-agent",
        "aud": "docpilot-server",
        "kid": "v1",
        "jti": uuid.uuid4().hex,
        "sub": username,
        "iat": now,
        "nbf": now - 1,
        "exp": now + 300,
    }
    encoded = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":")).encode()
    ).decode().rstrip("=")
    signature = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), encoded.encode(), hashlib.sha256).digest()
    ).decode().rstrip("=")
    return f"{encoded}.{signature}"


if __name__ == "__main__":
    main()
