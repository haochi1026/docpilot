# DocPilot Production Agent Service

This FastAPI service adds a LangChain/LangGraph orchestration layer while keeping deterministic authorization, retrieval, reservation transactions and idempotency in the Java services.

## Runtime responsibilities

- LangChain `create_agent` runs the ChatOllama model/tool loop.
- LangGraph checkpoints persist state by `thread_id`: SQLite is available for local development; PostgreSQL is required in production mode.
- `ModelCallLimitMiddleware` and `ToolCallLimitMiddleware` cap a single run and prevent unbounded loops.
- `HumanInTheLoopMiddleware` interrupts reservation, cancellation and check-in before the Java write API is called.
- A trusted `ToolRuntime` context carries username, user ID, role, knowledge-base ID and thread ID. These fields are not model-controlled tool arguments.
- The internal gateway applies timeout, exponential retry for safe/idempotent calls, circuit breaking and safe error mapping.
- Every Agent request can export Trace/Span data to AgentOps Hub; observability failure is best-effort and never breaks the business request.
- The Java caller authenticates with `X-Agent-Service-Key`; internal Java/Yanyue credentials remain server-side.
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
```

The exporter redacts common secret fields and stores only bounded previews. Tool calls become child spans containing tool name, status and latency.

## Run and test

From the DocPilot root:

```powershell
docker compose -f docker-compose.yml -f docker-compose.agent.yml up -d --build
docker compose -f docker-compose.yml -f docker-compose.agent.yml ps
```

Run Python tests from this directory:

```powershell
python -m pip install -r requirements-dev.txt
pytest
```

Start Yanyue separately with the same `YANYUE_AGENT_KEY` before testing reservation tools.
