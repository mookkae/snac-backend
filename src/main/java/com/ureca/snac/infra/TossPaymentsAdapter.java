package com.ureca.snac.infra;

import com.ureca.snac.infra.dto.response.TossCancelResponse;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.infra.dto.response.TossPaymentInquiryResponse;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.exception.TossRetryableException;
import com.ureca.snac.payment.mapper.PaymentCancelMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * 어댑터 토스페이먼츠 구현체
 * <p>
 * confirmPayment에 @Retryable
 * TossRetryableException 발생 시 최대 3회 재시도
 * 1초 초기 지연, 2배 증가 (1s -> 2s -> 4s)
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class TossPaymentsAdapter implements PaymentGatewayAdapter {

    // 외부 통신
    private final TossPaymentsClient tossPaymentsClient;
    // DTO 매퍼
    private final PaymentCancelMapper paymentCancelMapper;
    private final MeterRegistry meterRegistry;

    @Retryable(
            retryFor = {TossRetryableException.class},
            maxAttemptsExpression = "${retry.toss.max-attempts}",
            backoff = @Backoff(delayExpression = "${retry.toss.delay}",
                    multiplierExpression = "${retry.toss.multiplier}")
    )
    @Override
    public TossConfirmResponse confirmPayment(String paymentKey, String orderId, Long amount) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            log.debug("[Toss API] confirmPayment 호출. paymentKey: {}, orderId: {}", paymentKey, orderId);
            return tossPaymentsClient.confirmPayment(paymentKey, orderId, amount);
        } catch (Exception e) {
            result = "fail";
            throw e;
        } finally {
            sample.stop(Timer.builder("toss_api_duration").tag("method", "confirm").register(meterRegistry));
            Counter.builder("toss_api_call_total")
                    .tag("method", "confirm").tag("result", result)
                    .register(meterRegistry).increment();
        }
    }

    @Retryable(
            retryFor = {TossRetryableException.class},
            maxAttemptsExpression = "${retry.toss.max-attempts}",
            backoff = @Backoff(delayExpression = "${retry.toss.delay}",
                    multiplierExpression = "${retry.toss.multiplier}")
    )
    @Override
    public PaymentCancelResponse cancelPayment(String paymentKey, String reason) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            log.debug("[Toss API] cancelPayment 호출. paymentKey: {}", paymentKey);
            // 외부 통신에게 결제 취소 요청
            TossCancelResponse tossResponse = tossPaymentsClient.cancelPayment(paymentKey, reason);

            // 응답을 매퍼에 전달해서 서비스 DTO로 변경
            return paymentCancelMapper.toPaymentCancelResponse(tossResponse);
        } catch (Exception e) {
            result = "fail";
            throw e;
        } finally {
            sample.stop(Timer.builder("toss_api_duration").tag("method", "cancel").register(meterRegistry));
            Counter.builder("toss_api_call_total")
                    .tag("method", "cancel").tag("result", result)
                    .register(meterRegistry).increment();
        }
    }

    @Retryable(
            retryFor = {TossRetryableException.class},
            maxAttemptsExpression = "${retry.toss.max-attempts}",
            backoff = @Backoff(delayExpression = "${retry.toss.delay}",
                    multiplierExpression = "${retry.toss.multiplier}")
    )
    @Override
    public TossPaymentInquiryResponse inquirePaymentByOrderId(String orderId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            log.debug("[Toss API] inquirePaymentByOrderId 호출. orderId: {}", orderId);
            return tossPaymentsClient.inquirePaymentByOrderId(orderId);
        } catch (Exception e) {
            result = "fail";
            throw e;
        } finally {
            sample.stop(Timer.builder("toss_api_duration").tag("method", "inquire").register(meterRegistry));
            Counter.builder("toss_api_call_total")
                    .tag("method", "inquire").tag("result", result)
                    .register(meterRegistry).increment();
        }
    }
}
