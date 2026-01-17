package com.ureca.snac.outbox.service;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.config.AggregateExchangeMapper;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 스케줄러 (백업 + Pure Polling)
 * 1. Hybrid Push 실패 시 재시도 (SEND_FAIL)
 * 2. 서버 장애로 발행 못한 이벤트 처리 (INIT + 오래됨)
 * 3. Pure Polling 대상 이벤트 발행 (거래 등 순서 보장 필요)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;
    private final OutboxStatusUpdater statusUpdater;

    private static final int BATCH_SIZE = 100;
    private static final int STALE_THRESHOLD_MINUTES = 5;
    private static final int MAX_RETRY_COUNT = 3;

    /**
     * 발행 대기 중인 이벤트를 RabbitMQ로 발행
     * 1초 주기로 실행
     */
    @Scheduled(fixedDelay = 1000)
    public void publishPendingEvents() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES);

        // 짧은 조회
        List<Outbox> pendingEvents = outboxRepository.findPendingEvents(
                OutboxStatus.SEND_FAIL,
                OutboxStatus.INIT,
                threshold,
                MAX_RETRY_COUNT,
                PageRequest.of(0, BATCH_SIZE)
        ); // 커넥션 반환

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("[Outbox Scheduler] 발행 시작. 대상 이벤트 수: {}", pendingEvents.size());

        int successCount = 0;
        int failCount = 0;

        // 메시지 큐 발행 점유 없음
        for (Outbox outbox : pendingEvents) {
            try {
                publishToRabbitMQ(outbox);

                // 성공 시 상태 업데이트 (독립 트랜잭션)
                statusUpdater.markAsPublished(outbox.getId());
                successCount++;

                log.debug("[Outbox Scheduler] 발행 성공. outboxId: {}, eventType: {}, aggregateId: {}",
                        outbox.getId(), outbox.getEventType(), outbox.getAggregateId());

            } catch (Exception e) {
                // 실패 기록 (독립 트랜잭션)
                statusUpdater.markAsFailed(outbox.getId());
                failCount++;

                log.error("[Outbox Scheduler] 발행 실패. outboxId: {}, eventType: {}, retryCount: {}, error: {}",
                        outbox.getId(), outbox.getEventType(), outbox.getRetryCount(), e.getMessage());
            }
        }

        log.info("[Outbox Scheduler] 발행 완료. 성공: {}, 실패: {}", successCount, failCount);
    }

    /**
     * RabbitMQ로 메시지 발행
     *
     * @param outbox Outbox 엔티티
     */
    private void publishToRabbitMQ(Outbox outbox) {
        AggregateType aggregateType = AggregateType.from(outbox.getAggregateType());
        EventType eventType = EventType.from(outbox.getEventType());

        rabbitTemplate.convertAndSend(
                AggregateExchangeMapper.getExchange(aggregateType),
                eventType.getRoutingKey(),
                outbox.getPayload(),
                message -> {
                    message.getMessageProperties().setHeader("eventType", outbox.getEventType());
                    message.getMessageProperties().setHeader("aggregateId", outbox.getAggregateId());
                    return message;
                }
        );

        log.debug("[Outbox Scheduler] RabbitMQ 발행 성공. exchange: {}, routingKey: {}, aggregateId: {}",
                AggregateExchangeMapper.getExchange(aggregateType), eventType.getRoutingKey(), outbox.getAggregateId());
    }
}