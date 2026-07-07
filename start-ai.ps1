$ErrorActionPreference = 'Stop'
$env:COMPOSE_PARALLEL_LIMIT = '1'
docker info | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running.' }
docker compose -f docker-compose.yml -f docker-compose.ai.yml up -d ollama
if ($LASTEXITCODE -ne 0) { throw 'Failed to start Ollama.' }
docker compose -f docker-compose.yml -f docker-compose.ai.yml exec ollama ollama pull nomic-embed-text
if ($LASTEXITCODE -ne 0) { throw 'Failed to pull the embedding model.' }
docker compose -f docker-compose.yml -f docker-compose.ai.yml exec ollama ollama pull qwen2.5:3b
if ($LASTEXITCODE -ne 0) { throw 'Failed to pull the chat model.' }
docker compose -f docker-compose.yml -f docker-compose.ai.yml up -d --build
if ($LASTEXITCODE -ne 0) { throw 'Failed to start DocPilot AI mode.' }
Write-Host 'DocPilot AI mode is running: http://localhost:15174' -ForegroundColor Green
