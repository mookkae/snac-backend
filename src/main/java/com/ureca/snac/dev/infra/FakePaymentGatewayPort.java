package com.ureca.snac.dev.infra;

import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.dto.GatewayPaymentStatus;
import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.payment.port.out.dto.PaymentCancelResult;
import com.ureca.snac.payment.port.out.dto.PaymentInquiryResult;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

@Slf4j
@Component
@Qualifier("fake")
@RequiredArgsConstructor
public class FakePaymentGatewayPort implements PaymentGatewayPort {

    private final PaymentRepository paymentRepository;

    @Override
    public PaymentConfirmResult confirmPayment(String paymentKey, String orderId, Long amount) {
        log.warn("[Fake Adapter] 실제 API 호출 없이 더미 응답");
        return new PaymentConfirmResult(paymentKey, "개발용 충전", OffsetDateTime.now());
    }

    @Override
    public PaymentCancelResult cancelPayment(String paymentKey, String reason) {
        log.warn("[Fake Adapter] 실제 API 호출 없이 더미응답");

        Payment payment = paymentRepository.findByPaymentKeyWithMember(paymentKey)
                .orElseThrow(PaymentNotFoundException::new);

        return new PaymentCancelResult(
                paymentKey,
                payment.getAmount(),
                OffsetDateTime.now(),
                reason
        );
    }

    @Override
    public PaymentInquiryResult inquirePaymentByOrderId(String orderId) {
        log.warn("[Fake Adapter] 실제 API 호출 없이 더미 응답 (조회)");
        return new PaymentInquiryResult(GatewayPaymentStatus.DONE, "fake_payment_key", orderId, 10000L, "개발용", OffsetDateTime.now());
    }
}

