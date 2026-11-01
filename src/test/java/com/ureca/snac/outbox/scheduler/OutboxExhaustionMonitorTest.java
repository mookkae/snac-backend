package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.common.notification.SlackNotifier;
import com.ureca.snac.common.notification.dto.SlackMessage;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxExhaustionMonitorTest {

    private static final int MAX_RETRY = 300;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private SlackNotifier slackNotifier;

    private OutboxExhaustionMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new OutboxExhaustionMonitor(outboxRepository, slackNotifier, MAX_RETRY);
    }

    @Test
    @DisplayName("소진 이벤트가 있으면 Slack으로 알린다")
    void alertsWhenExhaustedEventExists() {
        // given
        given(outboxRepository.countByStatusAndRetryCountGreaterThanEqual(OutboxStatus.SEND_FAIL, MAX_RETRY))
                .willReturn(3L);

        // when
        monitor.monitor();

        // then
        verify(slackNotifier, times(1)).sendAsync(any(SlackMessage.class));
    }

    @Test
    @DisplayName("소진 이벤트가 없으면 알리지 않는다")
    void staysSilentWhenNoExhaustedEvent() {
        // given
        given(outboxRepository.countByStatusAndRetryCountGreaterThanEqual(OutboxStatus.SEND_FAIL, MAX_RETRY))
                .willReturn(0L);

        // when
        monitor.monitor();

        // then
        verify(slackNotifier, never()).sendAsync(any(SlackMessage.class));
    }

    @Test
    @DisplayName("수가 그대로면 다시 알리지 않고 늘면 다시 알린다")
    void alertsOnlyWhenCountChanges() {
        // given
        given(outboxRepository.countByStatusAndRetryCountGreaterThanEqual(OutboxStatus.SEND_FAIL, MAX_RETRY))
                .willReturn(3L, 3L, 5L);

        // when
        monitor.monitor();
        monitor.monitor();
        monitor.monitor();

        // then : 처음과 늘어난 때만
        verify(slackNotifier, times(2)).sendAsync(any(SlackMessage.class));
    }
}
