$ErrorActionPreference = 'Stop'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker was not found. Install and start Docker Desktop first.' }
$env:COMPOSE_PARALLEL_LIMIT = '1'
docker info | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running.' }
docker compose up -d --build
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose failed. Review the error above and run the script again.' }
Write-Host 'DocPilot is running: http://localhost:15174' -ForegroundColor Green
Write-Host 'Demo account: student / 123456'
