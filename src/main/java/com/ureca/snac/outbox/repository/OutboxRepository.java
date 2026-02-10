package com.ureca.snac.outbox.repository;

import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    /**
     * 발행 대기 중인 이벤트 조회 (배치 크기 제한)
     * <p>
     * 1. SEND_FAIL 상태 (발행 실패한 이벤트)
     * 2. INIT 상태 + 오래된 이벤트 (서버 장애로 발행 못한 이벤트)
     *
     * @param failStatus SEND_FAIL 상태
     * @param initStatus INIT 상태
     * @param threshold  기준 시간 (예: 5분 전)
     * @param maxRetry   최대 재시도 횟수
     * @param pageable   페이징 설정 (Limit 포함)
     * @return 발행 대기 중인 Outbox 목록 (ID 순서)
     */
    @Query("SELECT o FROM Outbox o " +
            "WHERE (o.status = :failStatus AND o.retryCount < :maxRetry) " +
            "OR (o.status = :initStatus AND o.createdAt < :threshold) " +
            "ORDER BY o.id ASC")
    List<Outbox> findPendingEvents(
            @Param("failStatus") OutboxStatus failStatus,
            @Param("initStatus") OutboxStatus initStatus,
            @Param("threshold") LocalDateTime threshold,
            @Param("maxRetry") int maxRetry,
            Pageable pageable
    );

    /**
     * 특정 Aggregate의 이벤트 조회 (디버깅/추적용)
     * 페이징으로 OOM 방지
     *
     * @param aggregateType Aggregate 타입
     * @param aggregateId   Aggregate ID
     * @param pageable      페이징 설정
     * @return 해당 Aggregate의 이벤트 목록
     */
    List<Outbox> findByAggregateTypeAndAggregateIdOrderByIdAsc(
            String aggregateType,
            Long aggregateId,
            Pageable pageable
    );

    /**
     * 발행 완료된 오래된 이벤트 일괄 삭제
     * <p>
     * idx_status_created 인덱스 활용 (status, created_at)
     * 아웃박스 삭제 스케줄러에서 주기적으로 호출
     * 각 호출이 독립 트랜잭션으로 처리됨
     *
     * @param threshold 보관 기준 시간
     * @param limit     한 번에 삭제할 최대 건수
     * @return 삭제된 행 수
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM outbox " +
            "WHERE status = 'PUBLISHED' " +
            "AND created_at < :threshold " +
            "LIMIT :limit",
            nativeQuery = true)
    int deleteOldPublishedEvents(
            @Param("threshold") LocalDateTime threshold,
            @Param("limit") int limit
    );

    /**
     * Outbox 상태를 INIT에서 PUBLISHED로 원자적 업데이트
     * INIT 또는 SEND_FAIL 상태 경쟁 상태(Race Condition) 방지
     * <p>
     * AsyncOutboxPublisher: INIT에서 PUBLISHED
     * OutboxPollingScheduler: SEND_FAIL, INIT에서 PUBLISHED
     *
     * @param id  Outbox ID
     * @param now 발행 시각
     * @return 업데이트된 행 수 (0 또는 1)
     */
    @Modifying
    @Query("UPDATE Outbox o " +
            "SET o.status = 'PUBLISHED', o.publishedAt = :now " +
            "WHERE o.id = :id " +
            "AND (o.status = 'INIT' OR o.status = 'SEND_FAIL')")
    int markAsPublished(
            @Param("id") Long id,
            @Param("now") LocalDateTime now
    );

    /**
     * Outbox 상태를 SEND_FAIL로 업데이트하고 재시도 횟수 증가
     * PUBLISHED 상태 제외조건 달고 원자적 업데이트로 경쟁 상태 방지
     *
     * @param id Outbox ID
     * @return 업데이트된 행 수 (0 또는 1)
     */
    @Modifying
    @Query("UPDATE Outbox o " +
            "SET o.status = 'SEND_FAIL', o.retryCount = o.retryCount + 1 " +
            "WHERE o.id = :id AND o.status != 'PUBLISHED'")
    int markAsFailedAndIncrementRetry(@Param("id") Long id);
}