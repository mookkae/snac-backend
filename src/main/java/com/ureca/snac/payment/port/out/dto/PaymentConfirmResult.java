package com.ureca.snac.payment.port.out.dto;

import java.time.OffsetDateTime;

public record PaymentConfirmResult(
        String paymentKey,
        String method,
        OffsetDateTime approvedAt
) {
}
