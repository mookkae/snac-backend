package com.ureca.snac.support.fixture;

import com.ureca.snac.payment.dto.PaymentCancelResponse;

import java.time.OffsetDateTime;

// PaymentCancelResponse 공유 테스트 Fixture
public class PaymentCancelResponseFixture {

    public static PaymentCancelResponse create(String paymentKey, Long amount, String reason) {
        return new PaymentCancelResponse(paymentKey, amount, OffsetDateTime.now(), reason);
    }
}
