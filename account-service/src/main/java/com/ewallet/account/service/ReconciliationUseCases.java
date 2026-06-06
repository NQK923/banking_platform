package com.ewallet.account.service;

import com.ewallet.account.model.LedgerEntryRecord;
import com.ewallet.account.model.ReconciliationReport;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ReconciliationUseCases {
    private final WalletStore store;

    public ReconciliationUseCases(WalletStore store) {
        this.store = store;
    }

    public ReconciliationReport run() {
        List<String> findings = new ArrayList<>();
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
                findings.add("balance drift for account " + projected.getKey());
            }
            BigDecimal cached = cachedBalances.get(projected.getKey());
            if (cached == null || cached.compareTo(projected.getValue()) != 0) {
                findings.add("cache drift for account " + projected.getKey());
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
        ReconciliationReport report = new ReconciliationReport(Instant.now(), findings.size(), findings.isEmpty(), totalByCurrency, findings);
        store.audit("RECONCILIATION", UUID.randomUUID(), "ReconciliationRun", "SYSTEM", null, Map.of("zeroDrift", String.valueOf(report.zeroDrift())), null);
        return report;
    }
}
