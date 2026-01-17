package com.ureca.snac.outbox.repository;

import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.fixture.OutboxFixture;
import com.ureca.snac.support.IntegrationTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OutboxRepository 테스트 (MySQL Testcontainers)
 * <p>
 * INIT/SEND_FAIL → PUBLISHED
 * 재시도 제한 (maxRetry) 실패 처리 로직 및 재시도 횟수 관리
 */
@Transactional
class OutboxRepositoryTest extends IntegrationTestSupport {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private EntityManager em;

    // markAsPublished 테스트
    @Test
    @DisplayName("성공 : INIT -> PUBLISHED 원자적 업데이트")
    void markAsPublished_FromInit_Success() {
        // given
        Outbox outbox = OutboxFixture.memberJoinInit(1L);
        outboxRepository.save(outbox);
        outboxRepository.flush();

        // when
        int updated = outboxRepository.markAsPublished(
                outbox.getId(),
                LocalDateTime.now()
        );

        em.clear();

        // then
        assertThat(updated).isEqualTo(1);

        Outbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(result.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("성공 : SEND_FAIL -> PUBLISHED 원자적 업데이트")
    void markAsPublished_FromSendFail_Success() {
        // given : SEND_FAIL
        Outbox outbox = OutboxFixture.failedWithRetry(1L, 1);
        outboxRepository.save(outbox);
        outboxRepository.flush();

        // when
        int updated = outboxRepository.markAsPublished(
                outbox.getId(),
                LocalDateTime.now()
        );

        em.clear();

        // then: 발행 서공
        assertThat(updated).isEqualTo(1);

        Outbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(result.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("동시성 : 이미 PUBLISHED 상태는 업데이트 실패 (경쟁 상태 방지)")
    void markAsPublished_AlreadyPublished_Fail() {
        // given : 이미 발행된 이벤트
        Outbox outbox = OutboxFixture.publishedOld(1L, 0);

        outboxRepository.save(outbox);
        outboxRepository.flush();

        // when : PUBLISHED 상태에서 다시 업데이트 시도
        int updated = outboxRepository.markAsPublished(
                outbox.getId(),
                LocalDateTime.now()
        );

        // then : 업데이트 실패 (중복 발행 방지)
        assertThat(updated).isEqualTo(0);
    }

    @Test
    @DisplayName("정합성 : 존재하지 않는 Outbox는 업데이트 실패")
    void markAsPublished_NotExists_Fail() {
        // when
        int updated = outboxRepository.markAsPublished(
                999L,
                LocalDateTime.now()
        );

        // then
        assertThat(updated).isEqualTo(0);
    }

    // markAsFailedAndIncrementRetry 테스트
    @Test
    @DisplayName("성공: INIT -> SEND_FAIL + retryCount 증가")
    void markAsFailed_FromInit_Success() {
        // given
        Outbox outbox = OutboxFixture.memberJoinInit(1L);
        outboxRepository.save(outbox);
        outboxRepository.flush();

        int initialRetryCount = outbox.getRetryCount();

        // when
        int updated = outboxRepository.markAsFailedAndIncrementRetry(outbox.getId());
        outboxRepository.flush();

        em.clear();

        // then
        assertThat(updated).isEqualTo(1);

        Outbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(OutboxStatus.SEND_FAIL);
        assertThat(result.getRetryCount()).isEqualTo(initialRetryCount + 1);
    }

    @Test
    @DisplayName("성공 : SEND_FAIL -> SEND_FAIL + retryCount 증가 (재실패)")
    void markAsFailed_FromSendFail_Success() {
        // given : 이미 SEND_FAIL 상태 (첫 실패)
        Outbox outbox = OutboxFixture.failedWithRetry(1L, 1);
        outboxRepository.save(outbox);
        outboxRepository.flush();

        // when : 두 번째 실패
        int updated = outboxRepository.markAsFailedAndIncrementRetry(outbox.getId());
        outboxRepository.flush();

        em.clear();

        // then : retryCount 2로 증가
        assertThat(updated).isEqualTo(1);

        Outbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(OutboxStatus.SEND_FAIL);
        assertThat(result.getRetryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("방어 : PUBLISHED 상태는 실패 처리 안 됨")
    void markAsFailed_AlreadyPublished_Fail() {
        // given : 발행 상태
        Outbox outbox = OutboxFixture.publishedOld(1L, 0);

        outboxRepository.save(outbox);
        outboxRepository.flush();

        // when : 실패 처리 DB에 쿼리 시도
        int updated = outboxRepository.markAsFailedAndIncrementRetry(outbox.getId());
        outboxRepository.flush();

        // 1차 캐시를 비워서, 다음 findById가 캐시가 아닌 진짜 DB 값을 가져오게
        em.clear();

        // then : 업데이트 안 됨
        assertThat(updated).isEqualTo(0);

        Outbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(result.getRetryCount()).isEqualTo(0);
    }

    // findPendingEvents 테스트
    @Test
    @DisplayName("성공 : SEND_FAIL 상태만 조회 (maxRetry 이내)")
    void findPendingEvents_SendFail_WithinMaxRetry() {
        // given : SEND_FAIL 3개 (retryCount: 0, 1, 2)
        outboxRepository.save(OutboxFixture.failedWithRetry(1L, 0));
        outboxRepository.save(OutboxFixture.failedWithRetry(2L, 1));
        outboxRepository.save(OutboxFixture.failedWithRetry(3L, 2));

        // SEND_FAIL 1개 (retryCount: 3 - 제외 대상)
        outboxRepository.save(OutboxFixture.failedWithRetry(4L, 3));

        outboxRepository.flush();

        // when : maxRetry = 3
        List<Outbox> result = outboxRepository.findPendingEvents(
                OutboxStatus.SEND_FAIL,
                OutboxStatus.INIT,
                LocalDateTime.now().minusMinutes(5),
                3,  // maxRetry
                PageRequest.of(0, 100)
        );

        // then : retryCount가 3보다 작은 것만 조회 (3개)
        assertThat(result).hasSize(3);
        assertThat(result).allMatch(o -> o.getRetryCount() < 3);
    }

    @Test
    @DisplayName("성공 : 오래된 INIT 상태 포함 조회")
    void findPendingEvents_IncludeStaleInit() {
        // given : SEND_FAIL 2개
        outboxRepository.save(OutboxFixture.failedWithRetry(1L, 1));
        outboxRepository.save(OutboxFixture.failedWithRetry(2L, 2));

        // 오래된 INIT 10분 전 - 조회 대상
        insertStaleInit(3L, 10);

        // 최근 INIT 1분 전 - 제외 대상
        insertStaleInit(4L, 1);

        outboxRepository.flush();

        // when : 5분 이전 기준
        List<Outbox> result = outboxRepository.findPendingEvents(
                OutboxStatus.SEND_FAIL,
                OutboxStatus.INIT,
                LocalDateTime.now().minusMinutes(5),
                3,
                PageRequest.of(0, 100)
        );

        // then : SEND_FAIL 2개만 (최근 INIT 제외)
        assertThat(result).hasSize(3);
        assertThat(result)
                .filteredOn(o -> o.getStatus() == OutboxStatus.INIT)
                .hasSize(1);
        assertThat(result)
                .filteredOn(o -> o.getStatus() == OutboxStatus.SEND_FAIL)
                .hasSize(2);
    }

    @Test
    @DisplayName("성공 : 배치 사이즈 제한")
    void findPendingEvents_BatchLimit() {
        // given : SEND_FAIL 150개
        for (int i = 0; i < 150; i++) {
            outboxRepository.save(OutboxFixture.failedWithRetry((long) i, 0));
        }
        outboxRepository.flush();

        // when : 최대 100개만 조회
        List<Outbox> result = outboxRepository.findPendingEvents(
                OutboxStatus.SEND_FAIL,
                OutboxStatus.INIT,
                LocalDateTime.now().minusMinutes(5),
                3,
                PageRequest.of(0, 100)
        );

        // then : 100개만 조회 (OOM 방지)
        assertThat(result).hasSize(100);
    }

    @Test
    @DisplayName("정합성 : PUBLISHED 상태는 제외")
    void findPendingEvents_ExcludePublished() {
        // given
        outboxRepository.save(OutboxFixture.failedWithRetry(1L, 1));
        outboxRepository.save(OutboxFixture.publishedOld(2L, 0));

        outboxRepository.flush();

        // when
        List<Outbox> result = outboxRepository.findPendingEvents(
                OutboxStatus.SEND_FAIL,
                OutboxStatus.INIT,
                LocalDateTime.now().minusMinutes(5),
                3,
                PageRequest.of(0, 100)
        );

        // then : SEND_FAIL만
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo(OutboxStatus.SEND_FAIL);
    }

    // 멱등성 테스트
    @Test
    @DisplayName("멱등성 : 동일 eventId 중복 저장 시 예외 발생")
    void preventDuplicateEventId_ThrowsException() {
        // given : 첫 번째 이벤트 저장
        String duplicateEventId = "duplicate-test-event-id";

        Outbox outbox1 = OutboxFixture.builder()
                .eventId(duplicateEventId)
                .aggregateId(1L)
                .build();

        outboxRepository.save(outbox1);
        outboxRepository.flush();

        // when & then : 같은 eventId로 저장 시도하면 예외 발생
        Outbox outbox2 = OutboxFixture.builder()
                .eventId(duplicateEventId)
                .aggregateId(1L)
                .build();

        assertThatThrownBy(() -> {
            outboxRepository.save(outbox2);
            outboxRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);

        em.clear();
    }

    @Test
    @DisplayName("멱등성 : 다른 eventId는 정상 저장")
    void allowDifferentEventId() {
        // given : 2개의 다른 이벤트
        Outbox outbox1 = OutboxFixture.memberJoinInit(1L);
        Outbox outbox2 = OutboxFixture.memberJoinInit(2L);

        // eventId가 다름
        assertThat(outbox1.getEventId()).isNotEqualTo(outbox2.getEventId());

        // when : 둘 다 저장
        outboxRepository.save(outbox1);
        outboxRepository.save(outbox2);
        outboxRepository.flush();

        // then : 2개 모두 저장됨
        List<Outbox> result = outboxRepository.findAll();
        assertThat(result).hasSize(2);
    }

    // 이게 Fixture 에서 객체를 생성해도 상속받아서 create 되어버려서 직접 SQL 문 삽입
    private void insertStaleInit(Long aggregateId, int minutesAgo) {
        LocalDateTime createdAt = LocalDateTime.now().minusMinutes(minutesAgo);

        em.createNativeQuery(
                        "INSERT INTO outbox " +
                                "(event_id, event_type, aggregate_type, aggregate_id, " +
                                "payload, status, retry_count, created_at, updated_at) " +
                                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                )
                .setParameter(1, UUID.randomUUID().toString())
                .setParameter(2, "MEMBER_JOIN")
                .setParameter(3, "MEMBER")
                .setParameter(4, aggregateId)
                .setParameter(5, String.format("{\"id\":%d}", aggregateId))
                .setParameter(6, "INIT")
                .setParameter(7, 0)
                .setParameter(8, createdAt)
                .setParameter(9, createdAt)
                .executeUpdate();
    }
}