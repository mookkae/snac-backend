package com.ureca.snac.infra.fixture;

import com.ureca.snac.infra.dto.response.TossCancelResponse;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.infra.dto.response.TossPaymentInquiryResponse;

import java.time.OffsetDateTime;
import java.util.List;

// Toss API 응답 테스트 Fixture (infra 도메인 전용)
public class TossResponseFixture {

    public static TossConfirmResponse createConfirmResponse(String paymentKey) {
        return new TossConfirmResponse(paymentKey, "카드", OffsetDateTime.now());
    }

    public static TossCancelResponse createCancelResponse(String paymentKey, Long amount, String reason) {
        TossCancelResponse.Cancel cancel = new TossCancelResponse.Cancel(
                amount, reason, OffsetDateTime.now()
        );
        return new TossCancelResponse(paymentKey, "snac_order_test", List.of(cancel));
    }

    public static TossPaymentInquiryResponse createInquiryResponse(
            String paymentKey, String orderId, String status) {
        OffsetDateTime approvedAt = "DONE".equals(status) ? OffsetDateTime.now() : null;
        return new TossPaymentInquiryResponse(
                paymentKey, orderId, status, "카드", 10000L, approvedAt
        );
    }
}
