package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 스케줄 스레드는 브로커 호출에 묶일 수 있어 쓰지 않고, 전용 스레드가 생성 시각만 갱신한다
 * 시계는 createdAt을 찍는 JPA 감사와 같은 JVM 기본 시간대를 쓴다. Clock 빈(Asia/Seoul 고정)을 쓰면 컨테이너 시간대에 따라 어긋난다
 */
@Slf4j
@Component
public class OutboxPendingAgeRefresher {

    public static final String AGE_METRIC = "outbox_oldest_pending_age_seconds";
    public static final String THRESHOLD_METRIC = "outbox_stale_threshold_seconds";

    private final OutboxRepository outboxRepository;
    private final int maxRetryCount;
    private final long refreshMs;
    private final double staleThresholdSeconds;
    private final OutboxPendingAge pendingAge = new OutboxPendingAge();
    private ScheduledExecutorService executor;

    public OutboxPendingAgeRefresher(
            OutboxRepository outboxRepository,
            MeterRegistry meterRegistry,
            @Value("${outbox.publisher.max-retry}") int maxRetryCount,
            @Value("${outbox.publisher.stale-threshold-minutes}") long staleThresholdMinutes,
            @Value("${outbox.pending-age.refresh-ms}") long refreshMs
    ) {
        this.outboxRepository = outboxRepository;
        this.maxRetryCount = maxRetryCount;
        this.refreshMs = refreshMs;
        this.staleThresholdSeconds = staleThresholdMinutes * 60.0;

        Clock clock = Clock.systemDefaultZone();
        Gauge.builder(AGE_METRIC, pendingAge, age -> age.ageSeconds(clock))
                .description("가장 오래 기다린 미발행 outbox 이벤트의 대기 시간(초)")
                .register(meterRegistry);
        Gauge.builder(THRESHOLD_METRIC, this, refresher -> refresher.staleThresholdSeconds)
                .description("폴러가 INIT 이벤트를 다시 보내기 시작하는 대기 시간(초)")
                .register(meterRegistry);
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "outbox-pending-age");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::refresh, 0, refreshMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    void refresh() {
        try {
            LocalDateTime init = outboxRepository.findOldestCreatedAt(OutboxStatus.INIT);
            LocalDateTime failed = outboxRepository.findOldestRetryableCreatedAt(OutboxStatus.SEND_FAIL, maxRetryCount);
            pendingAge.update(earlier(init, failed));
        } catch (Exception e) {
            // 예외가 밖으로 나가면 그 뒤 갱신이 영원히 멈춘다
            log.warn("[Outbox 미발행 대기 시간] 갱신 실패. 마지막 값 유지. error: {}", e.getMessage());
        }
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }
}
