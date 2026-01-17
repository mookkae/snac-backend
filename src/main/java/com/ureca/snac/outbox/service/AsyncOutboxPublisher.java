package com.ureca.snac.outbox.service;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.config.AggregateExchangeMapper;
import com.ureca.snac.config.AsyncConfig;
import com.ureca.snac.outbox.event.OutboxScheduledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Hybrid Push 전략의 즉시 발행 담당
 * AFTER_COMMIT 단계에서 OutboxScheduledEvent를 수신하여
 * 별도 스레드(@Async)에서 RabbitMQ로 즉시 발행
 * 실패 시 상태를 SEND_FAIL로 변경하여 스케줄러가 즉시 재시도
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncOutboxPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final OutboxStatusUpdater statusUpdater;

    /**
     * Outbox 이벤트를 즉시 발행 (Hybrid Push)
     * 트랜잭션 없이 실행하여 DB 커넥션 점유 최소화
     *
     * @param event OutboxScheduledEvent
     */
    @Async(AsyncConfig.EVENT_EXECUTOR_NAME)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void pushImmediately(OutboxScheduledEvent event) {
        try {
            log.debug("[Async Push] 즉시 발행 시작. outboxId: {}, eventType: {}, aggregateId: {}",
                    event.outboxId(), event.eventType(), event.aggregateId());

            // 1. RabbitMQ 발행 (트랜잭션 밖 - 커넥션 점유 없음)
            publishToRabbitMQ(
                    event.aggregateType(),
                    event.eventType(),
                    event.aggregateId(),
                    event.payload()
            );

            // 2. 상태 업데이트 (짧은 트랜잭션 - 원자적 업데이트)
            statusUpdater.markAsPublished(event.outboxId());

            log.info("[Async Push] 발행 완료. outboxId: {}, aggregateId: {}",
                    event.outboxId(), event.aggregateId());

        } catch (Exception e) {
            log.error("[Async Push] 발행 실패. 재시도 카운트 증가. outboxId: {}, aggregateId: {}, error: {}",
                    event.outboxId(), event.aggregateId(), e.getMessage());

            // 실패 기록 (SEND_FAIL, retryCount++)
            // 스케줄러가 다음 주기에 즉시 재시도
            statusUpdater.markAsFailed(event.outboxId());
        }
    }

    /**
     * RabbitMQ로 메시지 발행 (트랜잭션 없음)
     *
     * @param aggregateTypeName Aggregate 타입
     * @param eventTypeName     이벤트 타입
     * @param aggregateId       Aggregate ID (추적, 필터링용)
     * @param payload           JSON 페이로드
     */
    private void publishToRabbitMQ(
            String aggregateTypeName,
            String eventTypeName,
            Long aggregateId,
            String payload
    ) {
        AggregateType aggregateType = AggregateType.from(aggregateTypeName);
        EventType eventType = EventType.from(eventTypeName);

        String exchange = AggregateExchangeMapper.getExchange(aggregateType);

        rabbitTemplate.convertAndSend(
                exchange,
                eventType.getRoutingKey(),
                payload,
                message -> {
                    message.getMessageProperties().setHeader("eventType", eventTypeName);
                    message.getMessageProperties().setHeader("aggregateId", aggregateId);
                    return message;
                }
        );

        log.debug("[Async Push] RabbitMQ 발행 성공. exchange: {}, routingKey: {}, aggregateId: {}",
                exchange, eventType.getRoutingKey(), aggregateId);
    }
}