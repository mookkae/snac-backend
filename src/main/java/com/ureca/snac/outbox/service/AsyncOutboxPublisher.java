package com.ureca.snac.outbox.service;

import com.ureca.snac.config.AsyncConfig;
import com.ureca.snac.outbox.event.OutboxScheduledEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
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

    private final OutboxMessagePublisher messagePublisher;
    private final PushStatusWriter statusWriter;
    private final MeterRegistry meterRegistry;

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
            log.debug("[Async Push] 즉시 발행 시작. outboxId: {}, eventId: {}, eventType: {}",
                    event.outboxId(), event.eventId(), event.eventType());

            // 1. RabbitMQ 발행 (트랜잭션 밖 - 커넥션 점유 없음)
            messagePublisher.publish(
                    event.eventId(),
                    event.aggregateType(),
                    event.eventType(),
                    event.aggregateId(),
                    event.payload()
            );

            // 2. 상태 업데이트
            markAsPublished(event);

            Counter.builder("outbox_events_published_total")
                    .tag("result", "success")
                    .register(meterRegistry).increment();

            log.info("[Async Push] 발행 완료. outboxId: {}, eventId: {}, aggregateId: {}",
                    event.outboxId(), event.eventId(), event.aggregateId());

        } catch (Exception e) {
            log.error("[Async Push] 발행 실패. 재시도 카운트 증가. outboxId: {}, eventId: {}, error: {}",
                    event.outboxId(), event.eventId(), e.getMessage());

            Counter.builder("outbox_events_published_total")
                    .tag("result", "fail")
                    .register(meterRegistry).increment();

            // 실패 기록 (SEND_FAIL, retryCount++)
            // 스케줄러가 다음 주기에 즉시 재시도
            statusWriter.markAsFailed(event.outboxId());
        }
    }

    // 메시지는 이미 나갔다. 표시가 커넥션을 못 얻어도 실패로 적지 않는다
    // 행은 INIT으로 남아 폴러가 다시 보내고, 소비자가 중복을 걸러 낸다
    private void markAsPublished(OutboxScheduledEvent event) {
        try {
            statusWriter.markAsPublished(event.outboxId());
        } catch (CannotGetJdbcConnectionException e) {
            log.warn("[Async Push] 발행은 됐으나 상태 표시용 커넥션을 얻지 못함. 폴러가 다시 발행. outboxId: {}, eventId: {}",
                    event.outboxId(), event.eventId());
        }
    }
}
