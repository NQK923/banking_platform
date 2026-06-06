package com.ewallet.account.service;

import com.ewallet.account.model.LedgerEntryRecord;
import com.ewallet.account.model.ReconciliationReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationUseCases {
    private final WalletStore store;
    private final ReconciliationMetrics metrics;

    public ReconciliationUseCases(WalletStore store, ReconciliationMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    public ReconciliationReport run() {
        List<String> findings = new ArrayList<>();
        Map<String, UUID> affectedAccounts = new LinkedHashMap<>();
        Map<UUID, BigDecimal> projectedBalances = store.balancesSnapshot();
        Map<UUID, BigDecimal> cachedBalances = store.cacheBalancesSnapshot();
        Map<UUID, BigDecimal> ledgerBalances = new HashMap<>();
        Map<String, BigDecimal> totalByCurrency = new HashMap<>();
        List<LedgerEntryRecord> entries = store.ledgerEntriesSnapshot();
        for (LedgerEntryRecord entry : entries) {
            ledgerBalances.merge(entry.accountId(), entry.amount(), BigDecimal::add);
            totalByCurrency.merge(entry.currency(), entry.amount(), BigDecimal::add);
        }
        for (Map.Entry<UUID, BigDecimal> projected : projectedBalances.entrySet()) {
            BigDecimal ledger = ledgerBalances.getOrDefault(projected.getKey(), BigDecimal.ZERO);
            if (ledger.compareTo(projected.getValue()) != 0) {
                String finding = "balance drift for account " + projected.getKey();
                findings.add(finding);
                affectedAccounts.put(finding, projected.getKey());
            }
            BigDecimal cached = cachedBalances.get(projected.getKey());
            if (cached == null || cached.compareTo(projected.getValue()) != 0) {
                String finding = "cache drift for account " + projected.getKey();
                findings.add(finding);
                affectedAccounts.put(finding, projected.getKey());
            }
        }
        Map<UUID, BigDecimal> journalTotals = entries.stream()
            .collect(Collectors.groupingBy(LedgerEntryRecord::journalId, Collectors.reducing(BigDecimal.ZERO, LedgerEntryRecord::amount, BigDecimal::add)));
        for (Map.Entry<UUID, BigDecimal> journal : journalTotals.entrySet()) {
            if (journal.getValue().compareTo(BigDecimal.ZERO) != 0) {
                findings.add("unbalanced journal " + journal.getKey());
            }
        }
        for (Map.Entry<String, BigDecimal> total : totalByCurrency.entrySet()) {
            if (total.getValue().compareTo(BigDecimal.ZERO) != 0) {
                findings.add("closed-loop drift for " + total.getKey());
            }
        }
        Instant checkedAt = Instant.now();
        ReconciliationReport report = new ReconciliationReport(checkedAt, findings.size(), findings.isEmpty(), totalByCurrency, findings);
        UUID correlationId = UUID.randomUUID();
        store.persistReconciliationFindings(checkedAt, report.driftCount(), report.zeroDrift(), findings, affectedAccounts);
        store.freezeAccountsForReconciliation(Set.copyOf(affectedAccounts.values()), correlationId);
        metrics.record(report);
        store.audit("RECONCILIATION", correlationId, "ReconciliationRun", "SYSTEM", null, Map.of("zeroDrift", String.valueOf(report.zeroDrift())), correlationId);
        return report;
    }
}
