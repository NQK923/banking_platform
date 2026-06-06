package com.ewallet.contracts;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BackupRestoreContractTest {
    @Test
    void backupRestoreDocsAndScriptsDefinePitrRetentionAndRestoreDrill() throws Exception {
        String docs = Files.readString(Path.of("../docs/backup-restore.md"));
        String backupScript = Files.readString(Path.of("../scripts/postgres-backup.ps1"));
        String restoreDrillScript = Files.readString(Path.of("../scripts/postgres-restore-drill.ps1"));
        String pitrCheckScript = Files.readString(Path.of("../scripts/postgres-pitr-check.ps1"));

        for (String required : new String[] {
            "PITR",
            "WAL archiving",
            "Retain hourly backups for 30 days",
            "ledger_entries",
            "account_events",
            "account_snapshots",
            "audit_logs",
            "/api/admin/projections/rebuild-balances",
            "/api/reconciliation/run"
        }) {
            assertTrue(docs.contains(required), "Missing backup/restore doc token: " + required);
        }
        assertTrue(backupScript.contains("RetentionDays"), "Backup script must expose retention");
        assertTrue(backupScript.contains("pg_dump"), "Backup script must create a Postgres logical backup");
        assertTrue(backupScript.contains("Remove-Item"), "Backup script must prune expired backups");
        assertTrue(restoreDrillScript.contains("ScratchDatabase"), "Restore drill must use a scratch database");
        assertTrue(restoreDrillScript.contains("ledger_entries"), "Restore drill must verify ledger readability");
        assertTrue(pitrCheckScript.contains("SHOW wal_level"), "PITR check must inspect wal_level");
        assertTrue(pitrCheckScript.contains("SHOW archive_mode"), "PITR check must inspect archive_mode");
        assertTrue(pitrCheckScript.contains("SHOW archive_command"), "PITR check must inspect archive_command");
        assertTrue(pitrCheckScript.contains("SHOW archive_timeout"), "PITR check must inspect archive_timeout");
    }
}
