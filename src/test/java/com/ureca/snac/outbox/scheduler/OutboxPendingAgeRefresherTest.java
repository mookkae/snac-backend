package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.repository.OutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class OutboxPendingAgeRefresherTest {

    private static final int MAX_RETRY = 10;
    private static final long STALE_THRESHOLD_MINUTES = 3;

    @Mock
    private OutboxRepository outboxRepository;

    private SimpleMeterRegistry meterRegistry;
    private OutboxPendingAgeRefresher refresher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        refresher = new OutboxPendingAgeRefresher(
                outboxRepository, meterRegistry, MAX_RETRY, STALE_THRESHOLD_MINUTES, 5000);
    }

    @Test
    @DisplayName("INIT과 SEND_FAIL 중 더 오래된 행을 기준으로 삼는다")
    void usesOlderOfTwoStatuses() {
        // given
        given(outboxRepository.findOldestCreatedAt(OutboxStatus.INIT))
                .willReturn(LocalDateTime.now().minusSeconds(30));
        given(outboxRepository.findOldestRetryableCreatedAt(OutboxStatus.SEND_FAIL, MAX_RETRY))
                .willReturn(LocalDateTime.now().minusSeconds(300));

        // when
        refresher.refresh();

        // then
        assertThat(ageSeconds()).isBetween(299.0, 310.0);
    }

    // 갱신 작업이 예외를 밖으로 내면 그 뒤 실행이 멈추고, 대기 시간이 끝없이 늘어 알림이 계속 울린다
    @Test
    @DisplayName("조회가 실패해도 예외를 밖으로 던지지 않는다")
    void refreshSwallowsQueryFailure() {
        // given
        given(outboxRepository.findOldestCreatedAt(OutboxStatus.INIT))
                .willThrow(new DataAccessResourceFailureException("DB down"));

        // when & then
        assertThatCode(refresher::refresh).doesNotThrowAnyException();
    }

    // 알림 규칙이 이 값에 기댄다. 이름이나 단위(초)가 어긋나면 알림이 조용히 꺼진다
    @Test
    @DisplayName("재발행 기준 시간을 초로 낸다")
    void exposesStaleThreshold() {
        assertThat(meterRegistry.get(OutboxPendingAgeRefresher.THRESHOLD_METRIC).gauge().value())
                .isEqualTo(180.0);
    }

    private double ageSeconds() {
        return meterRegistry.get(OutboxPendingAgeRefresher.AGE_METRIC).gauge().value();
    }
}
