package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.repository.OutboxRepository;
import com.ureca.snac.outbox.service.OutboxMessagePublisher;
import com.ureca.snac.outbox.service.OutboxStatusUpdater;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Outbox 스케줄러 (백업 + Pure Polling)
 * 1. Hybrid Push 실패 시 재시도 (SEND_FAIL)
 * 2. 서버 장애로 발행 못한 이벤트 처리 (INIT + 오래됨)
 * 3. Pure Polling 대상 이벤트 발행 (거래 등 순서 보장 필요)
 */
@Slf4j
@Component
public class OutboxPollingScheduler {

    private final OutboxRepository outboxRepository;
    private final OutboxMessagePublisher messagePublisher;
    private final OutboxStatusUpdater statusUpdater;
    private final MeterRegistry meterRegistry;

    private final int batchSize;
    private final int staleThresholdMinutes;
    private final int maxRetryCount;

    // 종료 요청 플래그 (AtomicBoolean으로 스레드 안전성 보장)
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);

    public OutboxPollingScheduler(
            OutboxRepository outboxRepository,
            OutboxMessagePublisher messagePublisher,
            OutboxStatusUpdater statusUpdater,
            MeterRegistry meterRegistry,
            @Value("${outbox.publisher.batch-size}") int batchSize,
            @Value("${outbox.publisher.stale-threshold-minutes}") int staleThresholdMinutes,
            @Value("${outbox.publisher.max-retry}") int maxRetryCount
    ) {
        this.outboxRepository = outboxRepository;
        this.messagePublisher = messagePublisher;
        this.statusUpdater = statusUpdater;
        this.meterRegistry = meterRegistry;
        this.batchSize = batchSize;
        this.staleThresholdMinutes = staleThresholdMinutes;
        this.maxRetryCount = maxRetryCount;
    }

    // Graceful Shutdown 처리
    @PreDestroy
    public void shutdown() {
        if (shutdownRequested.compareAndSet(false, true)) {
            log.info("[Outbox Scheduler] 종료 요청. 진행 중인 배치 완료 후 중단됩니다.");
        }
    }

    // 1초 주기로 발행 대기 중인 이벤트를 RabbitMQ로 발행
    @Scheduled(fixedDelayString = "${outbox.publisher.fixed-delay-ms}")
    public void publishPendingEvents() {
        if (shutdownRequested.get()) {
            log.debug("[Outbox Scheduler] 종료 요청됨. 새 배치 건너뜀.");
            return;
        }

        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleThresholdMinutes);

        // 짧은 조회
        List<Outbox> pendingEvents = outboxRepository.findPendingEvents(
                OutboxStatus.SEND_FAIL,
                OutboxStatus.INIT,
                threshold,
                maxRetryCount,
                PageRequest.of(0, batchSize)
        ); // 커넥션 반환

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("[Outbox Scheduler] 발행 시작. 대상 이벤트 수: {}", pendingEvents.size());

        int publishedCount = 0;
        int failedCount = 0;

        // 메시지 큐 발행 점유 없음
        for (Outbox outbox : pendingEvents) {
            try {
                messagePublisher.publish(
                        outbox.getEventId(),
                        outbox.getAggregateType(),
                        outbox.getEventType(),
                        outbox.getAggregateId(),
                        outbox.getPayload()
                );

                // 성공 시 상태 업데이트 (독립 트랜잭션)
                statusUpdater.markAsPublished(outbox.getId());
                publishedCount++;

                Counter.builder("outbox_events_published_total")
                        .tag("result", "success")
                        .register(meterRegistry).increment();
                Counter.builder("outbox_polling_recovery_total")
                        .register(meterRegistry).increment();

                log.debug("[Outbox Scheduler] 발행 성공. outboxId: {}, eventId: {}, eventType: {}",
                        outbox.getId(), outbox.getEventId(), outbox.getEventType());

            } catch (Exception e) {
                // 실패 기록 (독립 트랜잭션)
                statusUpdater.markAsFailed(outbox.getId());
                failedCount++;

                Counter.builder("outbox_events_published_total")
                        .tag("result", "fail")
                        .register(meterRegistry).increment();

                log.error("[Outbox Scheduler] 발행 실패. outboxId: {}, eventId: {}, retryCount: {}, error: {}",
                        outbox.getId(), outbox.getEventId(), outbox.getRetryCount() + 1, e.getMessage());
            }
        }

        log.info("[Outbox Scheduler] 발행 완료. 성공: {}, 실패: {}", publishedCount, failedCount);
    }
}