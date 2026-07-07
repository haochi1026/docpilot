$ErrorActionPreference = 'Stop'
$env:COMPOSE_PARALLEL_LIMIT = '1'
docker info | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running.' }
docker compose -f docker-compose.yml -f docker-compose.rocketmq.yml up -d --build
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed. Review the error above and run the script again.' }
Write-Host 'DocPilot (RocketMQ mode) is running: http://localhost:15174' -ForegroundColor Green
