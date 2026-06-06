param(
    [string]$Container = "banking_platform-postgres-1",
    [string]$Database = "banking_platform"
)

$ErrorActionPreference = "Stop"

docker exec $Container psql -U banking -d $Database -c "SHOW wal_level"
docker exec $Container psql -U banking -d $Database -c "SHOW archive_mode"
docker exec $Container psql -U banking -d $Database -c "SHOW archive_command"
docker exec $Container psql -U banking -d $Database -c "SHOW archive_timeout"

Write-Host "Production PITR policy: wal_level must support replication, archive_mode must be on, and archive_command/archive_timeout must ship WAL to durable storage."
