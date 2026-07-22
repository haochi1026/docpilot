param(
  [string]$BaseUrl = "http://127.0.0.1:28081",
  [string]$Project = "docpilot-system-e2e",
  [int[]]$Levels = @(10, 20, 50),
  [switch]$KeepDocuments
)

$ErrorActionPreference = "Stop"
$docpilot = Split-Path -Parent $PSScriptRoot
$results = Join-Path $docpilot "tests/results/light-load"
New-Item -ItemType Directory -Force -Path $results | Out-Null

foreach ($level in $Levels) {
  $workers = [Math]::Min($level, 20)
  $output = Join-Path $results "load-$level-$(Get-Date -Format yyyyMMdd-HHmmss).json"
  Write-Output "Running upload/parse load level $level with $workers workers"
  docker stats --no-stream --format '{{json .}}' | Out-File -Encoding utf8 (Join-Path $results "load-$level-before.jsonl")
  python (Join-Path $docpilot "tests/pipeline_benchmark.py") `
    --base-url $BaseUrl --uploads $level --workers $workers `
    --size-kb 8 --chat-concurrency 0 `
    --output $output `
    $(if ($KeepDocuments) { "--keep-documents" })
  if ($LASTEXITCODE -ne 0) { throw "load level $level failed; see $output" }
  docker stats --no-stream --format '{{json .}}' | Out-File -Encoding utf8 (Join-Path $results "load-$level-after.jsonl")
  Write-Output "Report: $output"
}
