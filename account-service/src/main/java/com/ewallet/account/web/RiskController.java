package com.ewallet.account.web;

import com.ewallet.account.model.WalletTransaction;
import com.ewallet.account.risk.RiskDecision;
import com.ewallet.account.risk.RiskEvaluationRecord;
import com.ewallet.account.risk.RiskScoringService;
import com.ewallet.account.security.AuthenticatedUser;
import com.ewallet.account.service.TransferUseCases;
import com.ewallet.account.service.WalletStore;
import com.ewallet.common.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RiskController {
    private final WalletStore store;
    private final RiskScoringService riskScoringService;
    private final TransferUseCases transferUseCases;

    RiskController(WalletStore store, RiskScoringService riskScoringService, TransferUseCases transferUseCases) {
        this.store = store;
        this.riskScoringService = riskScoringService;
        this.transferUseCases = transferUseCases;
    }

    @PostMapping("/internal/ai/risk-score")
    RiskDecision score(@RequestBody RiskScoreRequest request) {
        return riskScoringService.evaluateTransfer(
            UUID.fromString(request.senderAccountId()),
            UUID.fromString(request.receiverAccountId()),
            Money.of(request.amount(), request.currency()),
            request.note(),
            request.idempotencyKey(),
            request.traceId() == null ? UUID.randomUUID() : UUID.fromString(request.traceId())
        );
    }

    @GetMapping("/api/admin/risk")
    PageResponse<RiskEvaluationRecord> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(required = false) String level,
        @RequestParam(required = false) String action
    ) {
        String levelFilter = level == null ? "" : level.trim().toUpperCase();
        String actionFilter = action == null ? "" : action.trim().toUpperCase();
        List<RiskEvaluationRecord> values = store.riskEvaluations().stream()
            .filter(item -> levelFilter.isBlank() || item.riskLevel().name().equals(levelFilter))
            .filter(item -> actionFilter.isBlank() || item.recommendedAction().name().equals(actionFilter))
            .toList();
        return page(values, page, size);
    }

    @GetMapping("/api/admin/risk/{id}")
    RiskEvaluationRecord get(@PathVariable UUID id) {
        return store.riskEvaluation(id);
    }

    @PostMapping("/api/admin/risk/{id}/approve")
    WalletTransaction approve(@PathVariable UUID id, @RequestBody ReviewRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return transferUseCases.approveRiskReview(id, request.reason(), user);
    }

    @PostMapping("/api/admin/risk/{id}/reject")
    WalletTransaction reject(@PathVariable UUID id, @RequestBody ReviewRequest request, @AuthenticationPrincipal AuthenticatedUser user) {
        return transferUseCases.rejectRiskReview(id, request.reason(), user);
    }

    private <T> PageResponse<T> page(List<T> values, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        int totalElements = values.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil(totalElements / (double) size);
        int fromIndex = Math.min(page * size, totalElements);
        int toIndex = Math.min(fromIndex + size, totalElements);
        return new PageResponse<>(new ArrayList<>(values.subList(fromIndex, toIndex)), page, size, totalElements, totalPages);
    }

    public record RiskScoreRequest(
        String senderAccountId,
        String receiverAccountId,
        String amount,
        String currency,
        String note,
        String idempotencyKey,
        String traceId
    ) {
    }

    public record ReviewRequest(String reason) {
    }

    public record PageResponse<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
    }
}
