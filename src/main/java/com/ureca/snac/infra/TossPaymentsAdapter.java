package com.ureca.snac.infra;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.infra.dto.response.TossCancelResponse;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.infra.dto.response.TossInquiryResponse;
import com.ureca.snac.infra.exception.*;
import com.ureca.snac.infra.mapper.PaymentCancelMapper;
import com.ureca.snac.payment.exception.AlreadyCanceledPaymentException;
import com.ureca.snac.payment.exception.PaymentAlreadySuccessException;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.dto.GatewayPaymentStatus;
import com.ureca.snac.payment.port.out.dto.PaymentCancelResult;
import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.payment.port.out.dto.PaymentInquiryResult;
import com.ureca.snac.payment.port.out.exception.GatewayNotCancelableException;
import com.ureca.snac.payment.port.out.exception.GatewayTransientException;
import com.ureca.snac.payment.port.out.exception.InsufficientCardBalanceException;
import com.ureca.snac.payment.port.out.exception.InvalidPaymentCardException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import static com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_API_ERROR;
import static com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_CONFIG_ERROR;

/**
 * 어댑터 토스페이먼츠 구현체
 * <p>
 * GatewayTransientException 발생 시 최대 3회 재시도 (1s → 2s → 4s).
 * infra 예외(TossRetryableException 등)는 이 경계에서 도메인 예외로 변환되어 외부로 노출되지 않는다.
 */
@Slf4j
@Primary
@Component
@Profile("!loadtest")
@RequiredArgsConstructor
public class TossPaymentsAdapter implements PaymentGatewayPort {

    private final TossPaymentsClient tossPaymentsClient;
    private final PaymentCancelMapper paymentCancelMapper;
    private final MeterRegistry meterRegistry;

    @Retryable(
            retryFor = {GatewayTransientException.class},
            maxAttemptsExpression = "${retry.toss.max-attempts}",
            backoff = @Backoff(delayExpression = "${retry.toss.delay}",
                    multiplierExpression = "${retry.toss.multiplier}")
    )
    @Override
    public PaymentConfirmResult confirmPayment(String paymentKey, String orderId, Long amount) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            log.debug("[Toss API] confirmPayment 호출. paymentKey: {}, orderId: {}", paymentKey, orderId);
            TossConfirmResponse tossResponse = tossPaymentsClient.confirmPayment(paymentKey, orderId, amount);
            return new PaymentConfirmResult(tossResponse.paymentKey(), tossResponse.method(), tossResponse.approvedAt());
        } catch (TossRetryableException e) {
            result = "fail";
            throw new GatewayTransientException(e);
        } catch (TossAlreadyProcessedPaymentException e) {
            result = "fail";
            throw new PaymentAlreadySuccessException();
        } catch (TossInvalidCardInfoException e) {
            result = "fail";
            throw new InvalidPaymentCardException();
        } catch (TossNotEnoughBalanceException e) {
            result = "fail";
            throw new InsufficientCardBalanceException();
        } catch (TossInvalidApiKeyException e) {
            result = "fail";
            throw new ExternalApiException(PAYMENT_GATEWAY_CONFIG_ERROR, e.getMessage(), e);
        } catch (Exception e) {
            result = "fail";
            throw new ExternalApiException(PAYMENT_GATEWAY_API_ERROR, e.getMessage(), e);
        } finally {
            sample.stop(Timer.builder("toss_api_duration").tag("method", "confirm").register(meterRegistry));
            Counter.builder("toss_api_call_total")
                    .tag("method", "confirm").tag("result", result)
                    .register(meterRegistry).increment();
        }
    }

    @Retryable(
            retryFor = {GatewayTransientException.class},
            maxAttemptsExpression = "${retry.toss.max-attempts}",
            backoff = @Backoff(delayExpression = "${retry.toss.delay}",
                    multiplierExpression = "${retry.toss.multiplier}")
    )
    @Override
    public PaymentCancelResult cancelPayment(String paymentKey, String reason) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            log.debug("[Toss API] cancelPayment 호출. paymentKey: {}", paymentKey);
            TossCancelResponse tossResponse = tossPaymentsClient.cancelPayment(paymentKey, reason);
            return paymentCancelMapper.toPaymentCancelResponse(tossResponse);
        } catch (TossRetryableException e) {
            result = "fail";
            throw new GatewayTransientException(e);
        } catch (TossAlreadyCanceledPaymentException e) {
            result = "fail";
            throw new AlreadyCanceledPaymentException();
        } catch (TossNotCancelablePaymentException e) {
            result = "fail";
            throw new GatewayNotCancelableException();
        } catch (TossInvalidApiKeyException e) {
            result = "fail";
            throw new ExternalApiException(PAYMENT_GATEWAY_CONFIG_ERROR, e.getMessage(), e);
        } catch (Exception e) {
            result = "fail";
            throw new ExternalApiException(PAYMENT_GATEWAY_API_ERROR, e.getMessage(), e);
        } finally {
            sample.stop(Timer.builder("toss_api_duration").tag("method", "cancel").register(meterRegistry));
            Counter.builder("toss_api_call_total")
                    .tag("method", "cancel").tag("result", result)
                    .register(meterRegistry).increment();
        }
    }

    @Retryable(
            retryFor = {GatewayTransientException.class},
            maxAttemptsExpression = "${retry.toss.max-attempts}",
            backoff = @Backoff(delayExpression = "${retry.toss.delay}",
                    multiplierExpression = "${retry.toss.multiplier}")
    )
    @Override
    public PaymentInquiryResult inquirePaymentByOrderId(String orderId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "success";
        try {
            log.debug("[Toss API] inquirePaymentByOrderId 호출. orderId: {}", orderId);
            TossInquiryResponse tossResponse = tossPaymentsClient.inquirePaymentByOrderId(orderId);
            return toInquiryResult(tossResponse);
        } catch (TossRetryableException e) {
            result = "fail";
            throw new GatewayTransientException(e);
        } catch (TossPaymentNotFoundException e) {
            result = "fail";
            throw new PaymentNotFoundException();
        } catch (TossInvalidApiKeyException e) {
            result = "fail";
            throw new ExternalApiException(PAYMENT_GATEWAY_CONFIG_ERROR, e.getMessage(), e);
        } catch (Exception e) {
            result = "fail";
            throw new ExternalApiException(PAYMENT_GATEWAY_API_ERROR, e.getMessage(), e);
        } finally {
            sample.stop(Timer.builder("toss_api_duration").tag("method", "inquire").register(meterRegistry));
            Counter.builder("toss_api_call_total")
                    .tag("method", "inquire").tag("result", result)
                    .register(meterRegistry).increment();
        }
    }

    private PaymentInquiryResult toInquiryResult(TossInquiryResponse tossResponse) {
        GatewayPaymentStatus status = switch (tossResponse.status()) {
            case "DONE" -> GatewayPaymentStatus.DONE;
            case "CANCELED", "ABORTED", "EXPIRED" -> GatewayPaymentStatus.CANCELED;
            case "READY" -> GatewayPaymentStatus.READY;
            case "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> GatewayPaymentStatus.IN_PROGRESS;
            default -> GatewayPaymentStatus.UNKNOWN;
        };
        return new PaymentInquiryResult(status, tossResponse.paymentKey(), tossResponse.orderId(), tossResponse.totalAmount(), tossResponse.method(), tossResponse.approvedAt());
    }
}
