package com.ureca.snac.asset.dto;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.TransactionType;

import java.time.format.DateTimeFormatter;

public record AssetHistoryResponse(
        Long id,
        String title,
        String category,   // category의 displayName
        String signedAmount,  // +3000, -5000
        Long balanceAfter,
        String createdAt,
        String paymentKey
) {

    public static AssetHistoryResponse from(AssetHistory history, String paymentKey) {

        String sign = history.getTransactionType() == TransactionType.DEPOSIT ? "+" : "-";
        String signedAMount = sign + String.format("%,d", history.getAmount());

        return new AssetHistoryResponse(
                history.getId(),
                history.getTitle(),
                history.getCategory().getDisplayName(),
                signedAMount,
                history.getBalanceAfter(),
                history.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                paymentKey
        );
    }
}