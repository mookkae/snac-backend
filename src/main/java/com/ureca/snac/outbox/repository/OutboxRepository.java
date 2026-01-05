package com.ureca.snac.outbox.repository;

import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    /**
     * 발행 대기 중인 이벤트 조회 (배치 크기 제한)
     * <p>
     * 조회 대상:
     * 1. SEND_FAIL 상태 (발행 실패한 이벤트)
     * 2. INIT 상태 + 오래된 이벤트 (서버 장애로 발행 못한 이벤트)
     *
     * @param failStatus SEND_FAIL 상태
     * @param initStatus INIT 상태
     * @param threshold  기준 시간 (예: 5분 전)
     * @param pageable   페이징 설정 (Limit 포함)
     * @return 발행 대기 중인 Outbox 목록 (ID 순서)
     */
    @Query("SELECT o FROM Outbox o " +
            "WHERE o.status = :failStatus " +
            "OR (o.status = :initStatus AND o.createdAt < :threshold) " +
            "ORDER BY o.id ASC")
    List<Outbox> findPendingEvents(
            @Param("failStatus") OutboxStatus failStatus,
            @Param("initStatus") OutboxStatus initStatus,
            @Param("threshold") LocalDateTime threshold,
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
     * 발행 완료된 오래된 이벤트 삭제용 조회
     *
     * @param status    PUBLISHED 상태
     * @param threshold 보관 기준 시간 (예: 30일 전)
     * @param pageable  페이징 설정
     * @return 삭제 대상 Outbox 목록
     */
    @Query("SELECT o FROM Outbox o " +
            "WHERE o.status = :status " +
            "AND o.publishedAt < :threshold " +
            "ORDER BY o.id ASC")
    List<Outbox> findOldPublishedEvents(
            @Param("status") OutboxStatus status,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable
    );

    /**
     * Outbox 상태를 INIT에서 PUBLISHED로 원자적 업데이트
     * 경쟁 상태(Race Condition) 방지용
     * REQUIRES_NEW 전파 속성으로 별도 트랜잭션에서 실행되므로 clearAutomatically 불필요
     *
     * @param id  Outbox ID
     * @param now 발행 시각
     * @return 업데이트된 행 수 (0 또는 1)
     */
    @Modifying
    @Query("UPDATE Outbox o " +
            "SET o.status = 'PUBLISHED', o.publishedAt = :now " +
            "WHERE o.id = :id AND o.status = 'INIT'")
    int markAsPublishedIfInit(
            @Param("id") Long id,
            @Param("now") LocalDateTime now
    );

    /**
     * Outbox 상태를 SEND_FAIL로 업데이트하고 재시도 횟수 증가
     * 원자적 업데이트로 경쟁 상태 방지
     *
     * @param id Outbox ID
     * @return 업데이트된 행 수 (0 또는 1)
     */
    @Modifying
    @Query("UPDATE Outbox o " +
            "SET o.status = 'SEND_FAIL', o.retryCount = o.retryCount + 1 " +
            "WHERE o.id = :id")
    int markAsFailedAndIncrementRetry(@Param("id") Long id);
}