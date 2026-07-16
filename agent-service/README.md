# DocPilot Agent Service

This service adds a LangChain/LangGraph agent orchestration layer without moving deterministic
authorization, retrieval, reservation transactions, or idempotency out of the Java services.

## Responsibilities

- `create_agent` runs the model/tool loop with ChatOllama.
- LangGraph SQLite checkpoints persist conversation state by `thread_id`.
- DocPilot retrieval, chunk source, and accessible-KB APIs are exposed as read-only tools.
- Yanyue resource queries are read-only tools.
- Reservation, cancellation, and check-in tools are guarded by `HumanInTheLoopMiddleware`; the
  graph pauses before the Java write endpoint is called and resumes only after approve/reject.
- Internal service keys are held by the service, not placed in prompts or model-visible tool args.

Run the full stack from the DocPilot directory:

```powershell
docker compose -f docker-compose.yml -f docker-compose.agent.yml up -d --build
```

Start Yanyue separately with the same `YANYUE_AGENT_KEY` before testing its tools.
