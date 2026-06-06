param(
    [string]$OutputDir = "backups",
    [string]$Container = "banking_platform-postgres-1",
    [int]$RetentionDays = 30
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")
$TargetDir = Join-Path $Root $OutputDir
New-Item -ItemType Directory -Force -Path $TargetDir | Out-Null

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backup = Join-Path $TargetDir "banking-platform-$timestamp.sql"
docker exec $Container pg_dump -U banking -d banking_platform | Set-Content -Path $backup

Get-ChildItem -Path $TargetDir -Filter "banking-platform-*.sql" |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$RetentionDays) } |
    Remove-Item -Force

Write-Host "Backup written to $backup"
