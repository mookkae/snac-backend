package com.ureca.snac.outbox.service;

import com.ureca.snac.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Outbox 상태 업데이트 전담 컴포넌트
 * AsyncOutboxPublisher와 OutboxPublisher(스케줄러)에서 공통 사용
 * REQUIRES_NEW 전파 속성으로 각 업데이트를 독립적으로 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxStatusUpdater {

    private final OutboxRepository outboxRepository;

    /**
     * Outbox 상태를 INIT에서 PUBLISHED로 원자적 업데이트
     * REQUIRES_NEW: 항상 새로운 트랜잭션 생성 (부분 실패 허용)
     * WHERE 조건으로 INIT 상태만 업데이트하여 중복 발행 방지
     *
     * @param outboxId Outbox ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsPublished(Long outboxId) {
        int updated = outboxRepository.markAsPublishedIfInit(
                outboxId,
                LocalDateTime.now()
        );

        if (updated == 0) {
            log.debug("[Outbox] 이미 발행됨 또는 존재하지 않음. outboxId: {}", outboxId);
        } else {
            log.debug("[Outbox] 상태 업데이트 완료. outboxId: {}, status: PUBLISHED", outboxId);
        }
    }

    /**
     * Outbox 상태를 SEND_FAIL로 업데이트하고 재시도 횟수 증가
     * REQUIRES_NEW: 실패 기록도 독립적으로 커밋
     * 쿼리 기반 업데이트로 성능 최적화 및 경쟁 상태 방지
     *
     * @param outboxId Outbox ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsFailed(Long outboxId) {
        int updated = outboxRepository.markAsFailedAndIncrementRetry(outboxId);

        if (updated == 0) {
            log.warn("[Outbox] 발행 실패 기록 실패. outboxId: {} (존재하지 않음)", outboxId);
        } else {
            log.warn("[Outbox] 발행 실패 기록 완료. outboxId: {}", outboxId);
        }
    }
}