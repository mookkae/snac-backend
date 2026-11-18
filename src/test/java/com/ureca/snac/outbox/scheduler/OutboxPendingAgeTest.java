package com.ureca.snac.outbox.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPendingAgeTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 18, 10, 0, 0);

    @Test
    @DisplayName("미발행 이벤트가 없으면 대기 시간은 0이다")
    void zeroWhenNothingPending() {
        // given
        OutboxPendingAge age = new OutboxPendingAge();
        age.update(null);

        // when & then
        assertThat(age.ageSeconds(clockAt(T0))).isZero();
    }

    // 갱신 스레드가 DB를 못 잡아 멈춰도 알림이 꺼지지 않아야 한다
    @Test
    @DisplayName("갱신이 없어도 시간이 가면 대기 시간이 늘어난다")
    void growsWithoutRefresh() {
        // given
        OutboxPendingAge age = new OutboxPendingAge();
        age.update(T0);

        // when & then
        assertThat(age.ageSeconds(clockAt(T0.plusSeconds(10)))).isEqualTo(10.0);
        assertThat(age.ageSeconds(clockAt(T0.plusSeconds(250)))).isEqualTo(250.0);
    }

    private static Clock clockAt(LocalDateTime time) {
        ZoneId zone = ZoneId.systemDefault();
        return Clock.fixed(time.atZone(zone).toInstant(), zone);
    }
}
