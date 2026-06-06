# Backup, Restore, and Retention

## Scope

PostgreSQL is the durable source for `ledger_entries`, `account_events`, `account_snapshots`, `account_balances`, `transactions`, and `audit_logs`. Redis is display/cache only and is rebuilt from PostgreSQL.

## v1 Policy

- Run `scripts/postgres-backup.ps1` at least hourly in production.
- Retain hourly backups for 30 days and archive daily backups according to compliance requirements.
- Enable PostgreSQL WAL archiving/PITR in the managed production database. The local script is a portable logical backup fallback for development and restore drills.
- Treat `ledger_entries`, `account_events`, `account_snapshots`, and `audit_logs` as immutable retention records.

## Restore Drill

1. Create a backup:

   ```powershell
   .\scripts\postgres-backup.ps1
   ```

2. Restore into a scratch database and verify ledger readability:

   ```powershell
   .\scripts\postgres-restore-drill.ps1 -BackupFile .\backups\banking-platform-YYYYMMDD-HHMMSS.sql
   ```

3. In a restored environment, rebuild display projections:

   ```http
   POST /api/admin/projections/rebuild-balances
   ```

4. Run reconciliation and require `zeroDrift=true` before serving traffic:

   ```http
   POST /api/reconciliation/run
   ```
