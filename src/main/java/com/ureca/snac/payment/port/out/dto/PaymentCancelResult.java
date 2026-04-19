package com.ureca.snac.payment.port.out.dto;

import java.time.OffsetDateTime;

public record PaymentCancelResult(
        String paymentKey,
        Long canceledAmount,
        OffsetDateTime canceledAt,
        String reason
) {
}
