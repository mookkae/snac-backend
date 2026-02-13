package com.ureca.snac.loadtest;

import com.ureca.snac.common.BaseCode;
import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.infra.dto.response.TossPaymentInquiryResponse;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 부하 테스트 전용 Toss Mock Adapter
 * <p>
 * confirm : 항상 성공, orderId -> paymentKey 매핑 저장
 * cancel : 대사 스케줄러 케이스 유발을 위한 확률적 실패
 * inquiry : confirm 된 건은 DONE, 아닌 건은 ExternalApiException
 */
@Slf4j
@Primary
@Component
@Profile("loadtest")
public class MockPaymentGatewayAdapter implements PaymentGatewayAdapter {

    @Value("${loadtest.fault.cancel-failure-rate}")
    private double cancelFailureRate;

    private final ConcurrentHashMap<String, String> confirmedPayments = new ConcurrentHashMap<>();

    @Override
    public TossConfirmResponse confirmPayment(String paymentKey, String orderId, Long amount) {
        log.debug("[Mock Toss] confirmPayment 성공. paymentKey: {}, orderId: {}", paymentKey, orderId);
        confirmedPayments.put(orderId, paymentKey);

        return new TossConfirmResponse(paymentKey, "카드", OffsetDateTime.now());
    }

    @Override
    public PaymentCancelResponse cancelPayment(String paymentKey, String reason) {
        if (ThreadLocalRandom.current().nextDouble() < cancelFailureRate) {
            log.warn("[Mock Toss] cancelPayment 장애 주입. paymentKey: {}", paymentKey);
            throw new ExternalApiException(BaseCode.TOSS_API_CALL_ERROR,
                    "[LoadTest] Mock cancel failure for reconciliation test");
        }

        log.debug("[Mock Toss] cancelPayment 성공. paymentKey: {}", paymentKey);
        return PaymentCancelResponse.builder()
                .paymentKey(paymentKey)
                .canceledAmount(0L)
                .canceledAt(OffsetDateTime.now())
                .reason(reason)
                .build();
    }

    @Override
    public TossPaymentInquiryResponse inquirePaymentByOrderId(String orderId) {
        String paymentKey = confirmedPayments.get(orderId);

        if (paymentKey == null) {
            log.debug("[Mock Toss] inquiry NOT_FOUND. orderId: {}", orderId);
            throw new ExternalApiException(BaseCode.TOSS_API_CALL_ERROR,
                    "[Mock] Payment not found: " + orderId);
        }

        log.debug("[Mock Toss] inquiry DONE. orderId: {}", orderId);
        return new TossPaymentInquiryResponse(
                paymentKey, orderId, "DONE", "카드", 0L, OffsetDateTime.now()
        );
    }
}
