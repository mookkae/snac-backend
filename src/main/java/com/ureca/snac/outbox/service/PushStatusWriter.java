package com.ureca.snac.outbox.service;

import com.ureca.snac.config.OutboxPushDataSourceConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 즉시 발행 결과를 outbox 행에 적는다
 * <p>
 * 가입 요청이 쓰는 기본 풀이 아니라 상태 표시 전용 풀로 실행한다. 문장마다 자동 커밋이다
 * WHERE 조건은 OutboxRepository의 markAsPublished, markAsFailedAndIncrementRetry와 같다
 */
@Slf4j
@Component
public class PushStatusWriter {

    private static final String MARK_PUBLISHED_SQL =
            "UPDATE outbox SET status = 'PUBLISHED', published_at = ? " +
                    "WHERE outbox_id = ? AND status IN ('INIT', 'SEND_FAIL')";

    private static final String MARK_FAILED_SQL =
            "UPDATE outbox SET status = 'SEND_FAIL', retry_count = retry_count + 1 " +
                    "WHERE outbox_id = ? AND status <> 'PUBLISHED'";

    private final JdbcTemplate jdbcTemplate;

    // Lombok 생성자는 @Qualifier를 옮기지 않아 기본 풀이 들어온다. 직접 쓴다
    public PushStatusWriter(
            @Qualifier(OutboxPushDataSourceConfig.PUSH_STATUS_DATA_SOURCE) DataSource dataSource
    ) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public void markAsPublished(Long outboxId) {
        int updated = jdbcTemplate.update(
                MARK_PUBLISHED_SQL, Timestamp.valueOf(LocalDateTime.now()), outboxId);

        if (updated == 0) {
            log.debug("[Outbox] 이미 발행됨 또는 존재하지 않음. outboxId: {}", outboxId);
        }
    }

    public void markAsFailed(Long outboxId) {
        int updated = jdbcTemplate.update(MARK_FAILED_SQL, outboxId);

        if (updated == 0) {
            log.warn("[Outbox] 발행 실패 기록 실패. outboxId: {} (존재하지 않거나 이미 발행)", outboxId);
        } else {
            log.warn("[Outbox] 발행 실패 기록 완료. outboxId: {}", outboxId);
        }
    }
}
