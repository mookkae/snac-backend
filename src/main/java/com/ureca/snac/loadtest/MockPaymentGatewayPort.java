package com.ureca.snac.loadtest;

import com.ureca.snac.common.BaseCode;
import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.dto.GatewayPaymentStatus;
import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.payment.port.out.dto.PaymentInquiryResult;
import com.ureca.snac.payment.port.out.dto.PaymentCancelResult;
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
public class MockPaymentGatewayPort implements PaymentGatewayPort {

    @Value("${loadtest.fault.cancel-failure-rate}")
    private double cancelFailureRate;

    private final ConcurrentHashMap<String, String> confirmedPayments = new ConcurrentHashMap<>();

    @Override
    public PaymentConfirmResult confirmPayment(String paymentKey, String orderId, Long amount) {
        log.debug("[Mock Toss] confirmPayment 성공. paymentKey: {}, orderId: {}", paymentKey, orderId);
        confirmedPayments.put(orderId, paymentKey);

        return new PaymentConfirmResult(paymentKey, "카드", OffsetDateTime.now());
    }

    @Override
    public PaymentCancelResult cancelPayment(String paymentKey, String reason) {
        if (ThreadLocalRandom.current().nextDouble() < cancelFailureRate) {
            log.warn("[Mock Toss] cancelPayment 장애 주입. paymentKey: {}", paymentKey);
            throw new ExternalApiException(BaseCode.PAYMENT_GATEWAY_API_ERROR,
                    "[LoadTest] Mock cancel failure for reconciliation test");
        }

        log.debug("[Mock Toss] cancelPayment 성공. paymentKey: {}", paymentKey);
        return new PaymentCancelResult(
                paymentKey,
                0L,
                OffsetDateTime.now(),
                reason
        );
    }

    @Override
    public PaymentInquiryResult inquirePaymentByOrderId(String orderId) {
        String paymentKey = confirmedPayments.get(orderId);

        if (paymentKey == null) {
            log.debug("[Mock Toss] inquiry NOT_FOUND. orderId: {}", orderId);
            throw new ExternalApiException(BaseCode.PAYMENT_GATEWAY_API_ERROR,
                    "[Mock] Payment not found: " + orderId);
        }

        log.debug("[Mock Toss] inquiry DONE. orderId: {}", orderId);
        return new PaymentInquiryResult(GatewayPaymentStatus.DONE, paymentKey, orderId, 0L, "카드", OffsetDateTime.now());
    }
}
