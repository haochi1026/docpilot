param(
  [string]$Project = "docpilot-acceptance",
  [int]$Port = 29081,
  [switch]$KeepStack
)

$ErrorActionPreference = "Stop"
$docpilot = Split-Path -Parent $PSScriptRoot
$compose = @(
  "-p", $Project,
  "-f", (Join-Path $docpilot "docker-compose.smoke.yml"),
  "-f", (Join-Path $docpilot "docker-compose.rocketmq.yml"),
  "-f", (Join-Path $docpilot "docker-compose.acceptance.yml")
)
$env:DOCPILOT_ACCEPTANCE_PORT = "$Port"
$fixture = Join-Path $docpilot "tests/fixtures/performance"
$output = Join-Path $docpilot "tests/results/acceptance_matrix_$(Get-Date -Format yyyyMMdd-HHmmss).json"

try {
  docker compose @compose up -d --build --wait --wait-timeout 300
  if ($LASTEXITCODE -ne 0) { throw "acceptance stack failed to start" }
  python (Join-Path $docpilot "tests/pipeline_benchmark.py") `
    --base-url "http://127.0.0.1:$Port" `
    --uploads 4 --workers 4 --chat-concurrency 0 `
    --fixture-dir $fixture --ollama --output $output
  if ($LASTEXITCODE -ne 0) { throw "acceptance benchmark failed" }
  Write-Output "Acceptance report: $output"
}
finally {
  if (-not $KeepStack) {
    docker compose @compose down --remove-orphans --volumes
  }
}
