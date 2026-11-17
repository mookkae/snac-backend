package com.ureca.snac.integration;

import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.event.OutboxScheduledEvent;
import com.ureca.snac.outbox.fixture.OutboxFixture;
import com.ureca.snac.outbox.service.AsyncOutboxPublisher;
import com.ureca.snac.outbox.service.OutboxMessagePublisher;
import com.ureca.snac.outbox.service.PushStatusWriter;
import com.ureca.snac.support.IntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 즉시 발행의 상태 표시 시나리오
 * <p>
 * 1. 실패 표시가 행을 INIT에서 벗어나게 하고 재시도 횟수를 1 올린다
 * 2. 이미 발행 완료된 행은 실패 표시로 바뀌지 않는다 (폴러가 먼저 보낸 뒤 늦게 온 실패가 되돌리지 못한다)
 * 3. 기본 풀의 커넥션이 전부 쓰이는 중에도 즉시 발행한 이벤트가 발행 완료로 표시된다
 * (표시가 기본 풀을 기다리면 행이 INIT으로 남아 3분 뒤 폴러가 다시 보낸다)
 */
// 발행기를 mock으로 바꾼 컨텍스트가 캐시에 남으면 그 폴러가 다른 테스트의 행을 mock으로 "발행"해 PUBLISHED로 바꾼다. 끝나면 닫는다
@DirtiesContext
class OutboxPushStatusIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AsyncOutboxPublisher asyncOutboxPublisher;

    @Autowired
    private PushStatusWriter pushStatusWriter;

    @MockitoBean
    private OutboxMessagePublisher messagePublisher;

    @Test
    @DisplayName("시나리오 1 : 실패 표시가 행을 INIT에서 벗어나게 하고 재시도 횟수를 1 올린다")
    void scenario1_marksRowAsFailedAndIncrementsRetry() {
        // given
        Outbox saved = outboxRepository.save(OutboxFixture.memberJoinInit(1L));

        // when
        pushStatusWriter.markAsFailed(saved.getId());

        // then : SEND_FAIL 행은 폴러가 1초 안에 다시 보내 PUBLISHED로 바꿀 수 있다
        // 그래서 상태는 "INIT이 아님"까지만 단언하고, 폴러가 건드리지 않는 재시도 횟수로 확인한다
        Outbox marked = outboxRepository.findById(saved.getId()).orElseThrow();
        assertThat(marked.getStatus()).isNotEqualTo(OutboxStatus.INIT);
        assertThat(marked.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("시나리오 2 : 이미 발행 완료된 행은 실패 표시로 바뀌지 않는다")
    void scenario2_keepsPublishedRowOnFailureMark() {
        // given
        Outbox saved = outboxRepository.save(OutboxFixture.publishedOld(1L, 1));

        // when
        pushStatusWriter.markAsFailed(saved.getId());

        // then
        Outbox kept = outboxRepository.findById(saved.getId()).orElseThrow();
        assertThat(kept.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(kept.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("시나리오 3 : 기본 풀 커넥션이 전부 쓰이는 중에도 즉시 발행한 이벤트가 발행 완료로 표시된다")
    void scenario3_marksPublishedWhileMainPoolIsExhausted() throws SQLException {
        // given : 가입 이벤트 한 건이 저장돼 있고, 기본 풀의 커넥션을 전부 가져간다
        Outbox saved = outboxRepository.save(OutboxFixture.memberJoinInit(1L));
        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        List<Connection> held = new ArrayList<>();

        try {
            for (int i = 0; i < poolSize; i++) {
                held.add(dataSource.getConnection());
            }

            // when
            asyncOutboxPublisher.pushImmediately(new OutboxScheduledEvent(
                    saved.getId(),
                    saved.getEventId(),
                    saved.getEventType(),
                    saved.getAggregateType(),
                    saved.getAggregateId(),
                    saved.getPayload()
            ));

            // then : 기본 풀이 풀리기를 기다리지 않고 표시된다. 5초는 Hikari 기본 대기 30초보다 짧게 잡은 값이다
            // 풀이 바닥나 있어 조회는 쥐고 있는 커넥션으로 한다
            Connection reader = held.get(0);
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(statusOf(reader, saved.getId())).isEqualTo("PUBLISHED"));
        } finally {
            for (Connection connection : held) {
                connection.close();
            }
        }
    }

    private static String statusOf(Connection connection, Long outboxId) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT status FROM outbox WHERE outbox_id = ?")) {
            statement.setLong(1, outboxId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }
}
