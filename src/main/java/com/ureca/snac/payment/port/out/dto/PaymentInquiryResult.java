package com.ureca.snac.payment.port.out.dto;

import java.time.OffsetDateTime;

public record PaymentInquiryResult(
        GatewayPaymentStatus status,
        String paymentKey,
        String orderId,
        Long totalAmount,
        String method,
        OffsetDateTime approvedAt
) {
    public boolean isDone() {
        return status == GatewayPaymentStatus.DONE;
    }

    public boolean isCanceledOrFailed() {
        return status == GatewayPaymentStatus.CANCELED || status == GatewayPaymentStatus.FAILED;
    }
}
