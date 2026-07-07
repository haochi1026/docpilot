param(
  [string]$ChatModel = 'qwen3.5:2b',
  [string]$EmbeddingModel = 'qwen3-embedding:0.6b'
)

$ErrorActionPreference = 'Stop'
$env:COMPOSE_PARALLEL_LIMIT = '1'

docker info | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running.' }

try {
  $tags = Invoke-RestMethod -Uri 'http://localhost:11434/api/tags' -TimeoutSec 5
} catch {
  throw 'Ollama is not reachable at http://localhost:11434. Start Ollama first.'
}

$installed = @($tags.models | ForEach-Object { $_.name })
if ($installed -notcontains $ChatModel) {
  throw "Chat model is not installed: $ChatModel. Run: ollama pull $ChatModel"
}
if ($installed -notcontains $EmbeddingModel) {
  throw "Embedding model is not installed: $EmbeddingModel. Run: ollama pull $EmbeddingModel"
}

$env:AI_MODE = 'openai'
$env:AI_BASE_URL = 'http://host.docker.internal:11434/v1'
$env:AI_API_KEY = 'ollama'
$env:AI_MODEL = $ChatModel
$env:EMBEDDING_MODE = 'openai'
$env:EMBEDDING_BASE_URL = 'http://host.docker.internal:11434/v1'
$env:EMBEDDING_API_KEY = 'ollama'
$env:EMBEDDING_MODEL = $EmbeddingModel

docker compose up -d --build server client
if ($LASTEXITCODE -ne 0) { throw 'Failed to start DocPilot with local Ollama.' }

Write-Host "DocPilot: http://localhost:15174" -ForegroundColor Green
Write-Host "Chat model: $ChatModel"
Write-Host "Embedding model: $EmbeddingModel"
Write-Host 'Open a managed knowledge base and click Rebuild vector index for existing documents.'
