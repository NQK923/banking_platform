param(
    [Parameter(Mandatory = $true)]
    [string]$BackupFile,
    [string]$Container = "banking_platform-postgres-1",
    [string]$ScratchDatabase = "banking_platform_restore_drill"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $BackupFile)) {
    throw "Backup file not found: $BackupFile"
}

docker exec $Container psql -U banking -d postgres -c "DROP DATABASE IF EXISTS $ScratchDatabase"
docker exec $Container psql -U banking -d postgres -c "CREATE DATABASE $ScratchDatabase"
Get-Content -LiteralPath $BackupFile | docker exec -i $Container psql -U banking -d $ScratchDatabase
docker exec $Container psql -U banking -d $ScratchDatabase -c "SELECT count(*) AS ledger_entries FROM ledger_entries"
docker exec $Container psql -U banking -d postgres -c "DROP DATABASE $ScratchDatabase"

Write-Host "Restore drill completed for $BackupFile"
