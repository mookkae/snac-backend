package com.ureca.snac.outbox.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 대기 시간이 아니라 생성 시각을 저장한다. 갱신이 멈춰도 대기 시간은 시간에 따라 늘어난다
 */
class OutboxPendingAge {

    private final AtomicReference<LocalDateTime> oldestPendingAt = new AtomicReference<>();

    void update(LocalDateTime oldestOrNull) {
        oldestPendingAt.set(oldestOrNull);
    }

    double ageSeconds(Clock clock) {
        LocalDateTime oldest = oldestPendingAt.get();
        if (oldest == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldest, LocalDateTime.now(clock)).toMillis() / 1000.0);
    }
}
