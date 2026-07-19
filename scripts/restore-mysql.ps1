param(
  [Parameter(Mandatory = $true)]
  [string]$BackupFile,
  [string]$TargetDatabase = "docpilot_restore",
  [switch]$Replace
)

$ErrorActionPreference = "Stop"
if ($TargetDatabase -notmatch '^[A-Za-z][A-Za-z0-9_]{0,63}$') {
  throw "TargetDatabase must be a simple MySQL identifier"
}
$source = (Resolve-Path -LiteralPath $BackupFile).Path
$containerOutput = docker compose ps -q mysql
$container = ([string]$containerOutput).Trim()
if (-not $container) { throw "DocPilot MySQL container is not running" }
$rootPassword = (docker exec $container printenv MYSQL_ROOT_PASSWORD).Trim()
if (-not $rootPassword) { throw "MySQL root password is unavailable in the container" }
$mysqlPasswordEnv = "MYSQL_PWD=$rootPassword"

$existsSql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME='$TargetDatabase'"
$existsQueryArg = "--execute=$existsSql"
$exists = (docker exec --env $mysqlPasswordEnv $container mysql --user=root --skip-column-names --silent $existsQueryArg).Trim()
if ($LASTEXITCODE -ne 0) { throw "Failed to inspect target database" }
if ($exists -eq "1" -and -not $Replace) {
  throw "Target database '$TargetDatabase' already exists; pass -Replace to recreate it"
}

$remote = "/tmp/docpilot-restore.sql"
try {
  docker cp $source "$($container):$remote"
  if ($LASTEXITCODE -ne 0) { throw "docker cp failed" }
  $quotedDatabase = ([char]96) + $TargetDatabase + ([char]96)
  $prepareSql = if ($Replace) {
    "DROP DATABASE IF EXISTS $quotedDatabase; CREATE DATABASE $quotedDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
  } else {
    "CREATE DATABASE $quotedDatabase CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
  }
  $prepareArg = "--execute=$prepareSql"
  docker exec --env $mysqlPasswordEnv $container mysql --user=root $prepareArg
  if ($LASTEXITCODE -ne 0) { throw "Failed to prepare target database" }
  $databaseArg = "--database=$TargetDatabase"
  $restoreArg = "--execute=source $remote"
  docker exec --env $mysqlPasswordEnv $container mysql --user=root $databaseArg $restoreArg
  if ($LASTEXITCODE -ne 0) { throw "Restore failed" }
  $tablesSql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='$TargetDatabase'"
  $tablesQueryArg = "--execute=$tablesSql"
  $tableCount = (docker exec --env $mysqlPasswordEnv $container mysql --user=root --skip-column-names --silent $tablesQueryArg).Trim()
  if (-not $tableCount -or [int]$tableCount -lt 1) { throw "Restore verification found no tables" }
  Write-Output "Restored $source to $TargetDatabase ($tableCount tables)"
} finally {
  docker exec $container rm -f $remote | Out-Null
}
