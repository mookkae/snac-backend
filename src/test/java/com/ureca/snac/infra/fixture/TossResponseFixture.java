package com.ureca.snac.infra.fixture;

import com.ureca.snac.infra.dto.response.TossCancelResponse;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.infra.dto.response.TossInquiryResponse;
import com.ureca.snac.payment.port.out.dto.GatewayPaymentStatus;
import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.payment.port.out.dto.PaymentInquiryResult;

import java.time.OffsetDateTime;
import java.util.List;

// Toss API 응답 테스트 Fixture (infra 도메인 전용)
public class TossResponseFixture {

    /** TossPaymentsClient 레벨 (TossPaymentsAdapterTest 전용) */
    public static TossConfirmResponse createConfirmResponse(String paymentKey) {
        return new TossConfirmResponse(paymentKey, "카드", OffsetDateTime.now());
    }

    public static TossCancelResponse createCancelResponse(String paymentKey, Long amount, String reason) {
        TossCancelResponse.Cancel cancel = new TossCancelResponse.Cancel(
                amount, reason, OffsetDateTime.now()
        );
        return new TossCancelResponse(paymentKey, "snac_order_test", List.of(cancel));
    }

    public static TossInquiryResponse createInquiryResponse(
            String paymentKey, String orderId, String status) {
        OffsetDateTime approvedAt = "DONE".equals(status) ? OffsetDateTime.now() : null;
        return new TossInquiryResponse(
                paymentKey, orderId, status, "카드", 10000L, approvedAt
        );
    }

    /** PaymentGatewayPort 레벨 — 어댑터를 MockitoBean으로 대체할 때 사용 */
    public static PaymentConfirmResult createConfirmResult(String paymentKey) {
        return new PaymentConfirmResult(paymentKey, "카드", OffsetDateTime.now());
    }

    public static PaymentInquiryResult createInquiryResult(String paymentKey, String orderId, String tossStatus) {
        GatewayPaymentStatus status = switch (tossStatus) {
            case "DONE" -> GatewayPaymentStatus.DONE;
            case "CANCELED", "ABORTED", "EXPIRED" -> GatewayPaymentStatus.CANCELED;
            case "READY" -> GatewayPaymentStatus.READY;
            case "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> GatewayPaymentStatus.IN_PROGRESS;
            default -> GatewayPaymentStatus.UNKNOWN;
        };
        OffsetDateTime approvedAt = GatewayPaymentStatus.DONE == status ? OffsetDateTime.now() : null;
        return new PaymentInquiryResult(status, paymentKey, orderId, 10000L, "카드", approvedAt);
    }
}
