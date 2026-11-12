package com.ureca.snac.integration;

import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.exception.BrokerUnavailableException;
import com.ureca.snac.outbox.fixture.OutboxFixture;
import com.ureca.snac.outbox.scheduler.OutboxPollingScheduler;
import com.ureca.snac.outbox.service.OutboxMessagePublisher;
import com.ureca.snac.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * 브로커 장기 장애 시나리오
 * <p>
 * 1. 브로커가 꺼진 동안 폴러가 여러 번 돌아도 재시도 횟수가 그대로이고, 브로커가 켜지면 그 행이 발행된다
 */
// 발행기를 mock으로 바꾼 컨텍스트가 캐시에 남으면 그 폴러가 다른 테스트의 행을 mock으로 "발행"해 PUBLISHED로 바꾼다. 끝나면 닫는다
@DirtiesContext
class OutboxBrokerOutageIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private OutboxPollingScheduler pollingScheduler;

    @MockitoBean
    private OutboxMessagePublisher messagePublisher;

    @Test
    @DisplayName("시나리오 1 : 브로커가 꺼진 동안 재시도 횟수가 그대로이고 켜지면 발행된다")
    void scenario1_retryCountHoldsThroughOutage() {
        // given
        Outbox saved = outboxRepository.save(OutboxFixture.failedWithRetry(1L, 1));
        doThrow(new BrokerUnavailableException(new ConnectException("Connection refused")))
                .when(messagePublisher)
                .publish(anyString(), anyString(), anyString(), anyLong(), anyString());

        // when : 브로커 장애 중 폴러가 반복 실행
        pollingScheduler.publishPendingEvents();
        pollingScheduler.publishPendingEvents();

        // then
        Outbox duringOutage = outboxRepository.findById(saved.getId()).orElseThrow();
        assertThat(duringOutage.getRetryCount()).isEqualTo(1);

        // when : 브로커 복구
        doNothing().when(messagePublisher)
                .publish(anyString(), anyString(), anyString(), anyLong(), anyString());
        pollingScheduler.publishPendingEvents();

        // then
        Outbox recovered = outboxRepository.findById(saved.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }
}
