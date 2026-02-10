package com.ureca.snac.payment.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
import com.ureca.snac.payment.service.PaymentInternalService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 결제 취소 보상 이벤트 리스너
 * 토스 취소 성공 후 DB 처리 실패 시 Wallet 환불 및 AssetHistory 기록
 * 재시도 불가 예외는 즉시 DLQ 이동
 * <p>
 * PaymentInternalService에 위임하여 @Transactional self-invocation 문제 해결
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentCancelCompensationListener {

    private final PaymentInternalService paymentInternalService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @RabbitListener(queues = RabbitMQQueue.PAYMENT_CANCEL_COMPENSATE_QUEUE)
    public void handleCompensationEvent(String payload) {
        String result = "success";
        Long paymentId = null;
        Long memberId = null;

        try {
            log.info("[결제 취소 보상] 이벤트 수신. payload: {}", payload);

            // 1. JSON 역직렬화
            PaymentCancelCompensationEvent event = deserializeEvent(payload);
            paymentId = event.paymentId();
            memberId = event.memberId();

            log.info("[결제 취소 보상] 처리 시작. paymentId: {}, memberId: {}", paymentId, memberId);

            // 2. 보상 처리 위임 (Wallet 환불 + AssetHistory 기록)
            paymentInternalService.processCompensation(event);

            log.info("[결제 취소 보상] 완료. paymentId: {}, memberId: {}", paymentId, memberId);

        } catch (JsonProcessingException e) {
            result = "dlq";
            log.error("[결제 취소 보상] JSON 파싱 실패. 즉시 DLQ 이동. payload: {}", payload, e);
            throw new AmqpRejectAndDontRequeueException("JSON 파싱 불가", e);

        } catch (Exception e) {
            result = "fail";
            log.error("[결제 취소 보상] 일시적 장애 발생. 재시도 예정. paymentId: {}, memberId: {}",
                    paymentId, memberId, e);
            throw e;

        } finally {
            Counter.builder("listener_message_processed_total")
                    .tag("queue", RabbitMQQueue.PAYMENT_CANCEL_COMPENSATE_QUEUE)
                    .tag("result", result)
                    .register(meterRegistry).increment();
        }
    }

    private PaymentCancelCompensationEvent deserializeEvent(String payload) throws JsonProcessingException {
        return objectMapper.readValue(payload, PaymentCancelCompensationEvent.class);
    }
}
