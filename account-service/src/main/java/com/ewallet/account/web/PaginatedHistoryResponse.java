package com.ewallet.account.web;

import com.ewallet.account.model.WalletTransaction;
import java.util.List;

public record PaginatedHistoryResponse(
    List<WalletTransaction> items,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
}
