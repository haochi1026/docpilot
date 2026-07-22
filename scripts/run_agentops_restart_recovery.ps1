param(
  [string]$Project = "docpilot-system-e2e",
  [string]$BaseUrl = "http://127.0.0.1:28100"
)

$ErrorActionPreference = "Stop"
$docpilot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $docpilot "docker-compose.system-e2e.yml"
$probe = Join-Path $docpilot "tests/agentops_restart_recovery.py"
$workerStopped = $false

try {
  docker compose -p $Project -f $composeFile stop agentops-worker
  if ($LASTEXITCODE -ne 0) { throw "failed to stop AgentOps worker" }
  $workerStopped = $true

  $prepared = @(python $probe prepare --base-url $BaseUrl)
  if ($LASTEXITCODE -ne 0 -or $prepared.Count -eq 0) { throw "failed to prepare recovery run" }
  $runId = [string]$prepared[-1]
  $runId = $runId.Trim()
  if (-not $runId) { throw "recovery run id is empty" }

  $sql = "UPDATE evaluation_run SET status='RUNNING', lease_owner='crashed-worker', heartbeat_at=NOW() - INTERVAL '5 minutes', lease_expires_at=NOW() - INTERVAL '1 minute' WHERE id='$runId'; UPDATE evaluation_dispatch SET status='SENT', claimed_by=NULL, lease_expires_at=NULL WHERE run_id='$runId';"
  $sql | docker compose -p $Project -f $composeFile exec -T agentops-postgres psql -v ON_ERROR_STOP=1 -U agentops -d agentops
  if ($LASTEXITCODE -ne 0) { throw "failed to inject expired worker lease" }

  docker compose -p $Project -f $composeFile restart agentops-api
  if ($LASTEXITCODE -ne 0) { throw "failed to restart AgentOps API" }
  docker compose -p $Project -f $composeFile up -d --wait --wait-timeout 120 agentops-api
  if ($LASTEXITCODE -ne 0) { throw "AgentOps API did not recover readiness" }

  docker compose -p $Project -f $composeFile start agentops-worker
  if ($LASTEXITCODE -ne 0) { throw "failed to restart AgentOps worker" }
  $workerStopped = $false

  python $probe verify --base-url $BaseUrl --run-id $runId --timeout 180
  if ($LASTEXITCODE -ne 0) { throw "recovered evaluation run did not complete" }
}
finally {
  if ($workerStopped) {
    docker compose -p $Project -f $composeFile start agentops-worker | Out-Null
  }
}
