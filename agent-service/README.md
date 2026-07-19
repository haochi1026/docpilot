# DocPilot Production Agent Service

This FastAPI service adds a LangChain/LangGraph orchestration layer while keeping deterministic authorization, retrieval, document state transitions, Outbox publishing and idempotency in the Java service.

## Runtime responsibilities

- LangChain supplies ChatOllama messages and tools; an explicit LangGraph state graph has a required-retrieval branch and a deterministic document-operations branch. The latter enforces diagnose → state check → write plan → HITL → execute instead of asking the model to invent a transition.
- LangGraph checkpoints persist state by `thread_id`: SQLite is available for local development; PostgreSQL is required in production mode.
- `ModelCallLimitMiddleware` and `ToolCallLimitMiddleware` cap a single run and prevent unbounded loops.
- `policy_gate` interrupts `retry_document_parsing` before the Java write API is called. The Java service persists the approval record and issues a one-time HMAC-bound token after the user approves.
- A trusted `ToolRuntime` context carries username, user ID, role, knowledge-base ID and thread ID. These fields are not model-controlled tool arguments.
- The internal gateway applies timeout, exponential retry for safe/idempotent calls, circuit breaking and safe error mapping.
- Every Agent request exports Trace/Span data to AgentOps Hub when enabled; the streaming graph is advanced inside one captured request context so model/tool spans survive Starlette worker-context changes without process-global trace state. Interrupted and completed runs both expose `trace_id` and export status. Observability failure is best-effort and never breaks the business request.
- Knowledge answers must pass claim-level citation and numeric-fact guards. If model citation repair still contains an unsupported tail, a deterministic fallback may return one verbatim, high-overlap evidence sentence with its source ID; weak matches remain a refusal.
- Knowledge questions are deterministically planned through `search_knowledge_base`; no-evidence runs receive a safe refusal and the final guard rejects out-of-range citations. A missing citation gets one evidence-bounded repair attempt and otherwise fails closed.
- The Java caller authenticates with `X-Agent-Service-Key`; internal Java credentials remain server-side.
- `/v1/agent/evaluate` aggregates one run into deterministic JSON for regression evaluation.

## Endpoints

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /health/live` | public | process liveness |
| `GET /health/ready` | public | runtime configuration readiness |
| `GET /metrics` | public | Prometheus text metrics |
| `POST /v1/agent/chat/stream` | service key | NDJSON streaming Agent run |
| `POST /v1/agent/evaluate` | service key | non-streaming evaluation adapter |

The two POST endpoints require `X-Agent-Service-Key`.

## Local and production checkpoints

```dotenv
# Local-only
CHECKPOINT_BACKEND=sqlite
CHECKPOINT_PATH=./data/checkpoints.sqlite

# Production-oriented Compose mode
CHECKPOINT_BACKEND=postgres
CHECKPOINT_DSN=postgresql://user:password@agent-postgres:5432/docpilot_agent?sslmode=disable
```

`APP_ENV=production` rejects SQLite checkpoints and known default service credentials.

## AgentOps trace export

```dotenv
AGENTOPS_ENABLED=true
AGENTOPS_BASE_URL=http://host.docker.internal:18100
AGENTOPS_API_KEY=<platform key>
AGENTOPS_IDENTITY_TOKEN_SECRET=<same signing secret as AgentOps IDENTITY_TOKEN_SECRET>
AGENTOPS_IDENTITY_SUBJECT=docpilot-agent
AGENTOPS_IDENTITY_TENANT=default
AGENTOPS_IDENTITY_ROLE=OPERATOR
```

The exporter redacts common secret fields and stores only bounded previews. Tool calls become child spans containing tool name, status and latency. In production, prefer the signing secret: the client mints a new five-minute HMAC identity for each request. `AGENTOPS_IDENTITY_TOKEN` remains available only for a short-lived externally refreshed token.

## Run and test

From the DocPilot root:

```powershell
docker compose -f docker-compose.yml -f docker-compose.agent.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.agent.yml ps
```

Run Python tests from this directory:

```powershell
python -m pip install -r requirements-dev.lock
pytest
```

The Agent is self-contained within DocPilot and does not depend on another business system. Test the document recovery flow with a document whose parse status is `FAILED`.
