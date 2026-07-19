param(
  [string]$OutputDir = "",
  [int]$RetentionDays = 14
)
$ErrorActionPreference = "Stop"
if (-not $OutputDir) { $OutputDir = Join-Path $PSScriptRoot "..\backups" }
$resolved = [System.IO.Path]::GetFullPath($OutputDir)
New-Item -ItemType Directory -Force -Path $resolved | Out-Null
$containerOutput = docker compose ps -q mysql
$container = ([string]$containerOutput).Trim()
if (-not $container) { throw "DocPilot MySQL container is not running" }
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$remote = "/tmp/docpilot-backup.sql"
$file = Join-Path $resolved "docpilot-$stamp.sql"
try {
  $rootPassword = (docker exec $container printenv MYSQL_ROOT_PASSWORD).Trim()
  if (-not $rootPassword) { throw "MySQL root password is unavailable in the container" }
  docker exec --env "MYSQL_PWD=$rootPassword" $container mysqldump --user=root --single-transaction --routines --events --result-file=$remote docpilot
  if ($LASTEXITCODE -ne 0) { throw "mysqldump failed" }
  docker cp "${container}:${remote}" $file
  if ($LASTEXITCODE -ne 0) { throw "docker cp failed" }
} finally {
  docker exec $container rm -f $remote | Out-Null
}
$cutoff = (Get-Date).ToUniversalTime().AddDays(-[Math]::Max(1, $RetentionDays))
Get-ChildItem -LiteralPath $resolved -Filter "docpilot-*.sql" -File |
  Where-Object { $_.LastWriteTimeUtc -lt $cutoff } |
  ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
Write-Output $file
