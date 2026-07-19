# DocPilot production hardening

## Schema and data lifecycle

MySQL forward migrations are managed only by Flyway under `server/src/main/resources/db/migration`. `V1__baseline.sql` is the canonical initial schema; `schema.sql` is retained as a legacy reference and is not executed at startup. New changes must be added as numbered migrations. `V2__production_consistency.sql` adds document leases, embedding model metadata, tombstones, Outbox dead-letter fields and foreign keys. The migration is validated on startup and destructive `clean` is disabled.

Use `scripts/backup-mysql.ps1` before applying migrations. The script uses `mysqldump --single-transaction` and removes local dumps older than 14 days.

## Document parse safety and revisions

Claiming a document creates a parse job token and lease. Workers renew the lease and all terminal writes are fenced by the token. A stale worker cannot overwrite a newer parse or a deleted document. Deletion leaves a `DELETED` tombstone and removes chunks before the row is hidden from normal queries.

Every parse writes a new `document_revision`. Draft chunks are isolated by `revision_no`, and embeddings are written under a shadow model alias. Only a complete shadow vector set is promoted and only then does the MySQL transaction flip `active_revision`; a failed parse leaves the previous published revision searchable. The scheduled retention task keeps the newest `DOCUMENT_REVISION_RETENTION_COUNT` revisions and never removes the active or `PROCESSING` revision. Source payloads expose the immutable chunk ID and revision number so a recorded answer can be traced to the exact published revision.

## Outbox operations

The dispatcher selects up to 32 messages per tick and atomically claims each row with a worker owner. A five-minute sending lease recovers crashes, retry uses backoff, and five failures move a message to `DEAD`. Administrators can inspect `/api/admin/outbox/metrics`, Prometheus backlog/age gauges and replay a dead message with `POST /api/admin/outbox/{id}/replay`.

## Embedding migration and checkpoints

`POST /api/kbs/{id}/embedding-migrate` builds a complete shadow set and promotes it only after count validation. pgvector tables are dimension-specific (`document_chunk_vector_<dimension>`), so model migrations do not mix vector spaces; the repository validates the active dimension at startup and deletes abandoned shadow aliases after 24 hours. Agent checkpoints can be cleaned through `/v1/admin/checkpoints/cleanup`, and a periodic task applies `CHECKPOINT_RETENTION_DAYS` while retaining the newest checkpoint for every thread.

The Agent container runs as a non-root user and installs the checked-in `requirements.lock`. `scripts/scan-images.ps1` runs a Trivy HIGH/CRITICAL scan. In production, configure AgentOps with `AUTH_MODE=hmac` and a secret, then issue short-lived identity tokens with `scripts/issue_identity_token.py`; actor/tenant/role headers are not trusted in that mode.
