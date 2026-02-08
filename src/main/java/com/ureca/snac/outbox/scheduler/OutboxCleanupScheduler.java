package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.outbox.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Outbox 테이블 정리 스케줄러
 * <p>
 * PUBLISHED 상태의 오래된 이벤트를 주기적으로 삭제
 * 배치 단위로 삭제하여 DB 부하 방지
 * Repository에서 처리하는 독립 트랜잭션
 */
@Slf4j
@Component
public class OutboxCleanupScheduler {

    private final OutboxRepository outboxRepository;
    private final int retentionDays;
    private final int batchSize;

    public OutboxCleanupScheduler(
            OutboxRepository outboxRepository,
            @Value("${outbox.cleanup.retention-days}") int retentionDays,
            @Value("${outbox.cleanup.batch-size}") int batchSize
    ) {
        this.outboxRepository = outboxRepository;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    // 매일 새벽 3시에 보관 기간이 지난 발행 PUBLISHED 된 이벤트 배치 단위로 반복 삭제
    @Scheduled(cron = "${outbox.cleanup.cron}")
    public void cleanupOldPublishedEvents() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(retentionDays);

        log.info("[Outbox Cleanup] 정리 시작. 기준 시각: {} ({}일 이전), 배치 크기: {}",
                threshold, retentionDays, batchSize);

        int totalDeleted = 0;
        int deletedInBatch;

        // 배치 단위로 반복 삭제 (각 Repository 호출이 독립 트랜잭션)
        do {
            try {
                deletedInBatch = outboxRepository.deleteOldPublishedEvents(threshold, batchSize);
                totalDeleted += deletedInBatch;

                if (deletedInBatch > 0) {
                    log.debug("[Outbox Cleanup] 배치 삭제 완료. 삭제: {}, 누적: {}",
                            deletedInBatch, totalDeleted);
                }
            } catch (Exception e) {
                log.error("[Outbox Cleanup] 배치 삭제 실패. 누적 삭제: {}, 다음 스케줄에 재시도. error: {}",
                        totalDeleted, e.getMessage());
                break;
            }
        } while (deletedInBatch == batchSize);

        log.info("[Outbox Cleanup] 정리 완료. 총 삭제된 이벤트 수: {}", totalDeleted);
    }
}
