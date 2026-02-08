package com.ureca.snac.infra.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentInquiryResponse(
        String paymentKey,
        String orderId,
        String status,
        String method,
        Long totalAmount,
        OffsetDateTime approvedAt
) {
    private static final Set<String> CANCELED_OR_FAILED_STATUSES =
            Set.of("CANCELED", "ABORTED", "EXPIRED");

    public boolean isDone() {
        return "DONE".equals(status);
    }

    public boolean isCanceledOrFailed() {
        return CANCELED_OR_FAILED_STATUSES.contains(status);
    }
}
