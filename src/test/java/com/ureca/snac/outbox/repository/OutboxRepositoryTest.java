package com.ureca.snac.outbox.repository;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.fixture.OutboxFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OutboxRepository 테스트
 * <p>
 * - 원자성 업데이트 (경쟁 상태 방지)
 * - 실패 처리 로직 및 재시도 횟수 관리
 * - 발행 대기 이벤트 조회
 */
@DataJpaTest
@ActiveProfiles("test")
@EntityScan(basePackages = "com.ureca.snac.outbox.entity")
@EnableJpaRepositories(basePackages = "com.ureca.snac.outbox.repository")
class OutboxRepositoryTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("원자적 업데이트 : INIT 상태만 PUBLISHED로 변경 성공")
    void markAsPublishedIfInit_Success() {
        // given
        Outbox outbox = OutboxFixture.memberJoinInit(1L);
        outboxRepository.save(outbox);
        em.flush();
        em.clear();

        // when
        int updated = outboxRepository.markAsPublishedIfInit(
                outbox.getId(),
                LocalDateTime.now()
        );

        // then
        assertThat(updated).isEqualTo(1);

        Outbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(result.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("원자적 업데이트 : INIT이 아닌 상태는 업데이트 실패 (경쟁 상태 방지)")
    void markAsPublishedIfInit_Fail_AlreadyPublished() {
        // given: 이미 PUBLISHED 상태 (다른 스레드가 먼저 처리)
        Outbox outbox = OutboxFixture.builder()
                .eventType(EventType.MEMBER_JOIN)
                .aggregateType(AggregateType.MEMBER)
                .aggregateId(1L)
                .status(OutboxStatus.PUBLISHED)
                .build();

        outboxRepository.save(outbox);
        em.flush();
        em.clear();

        // when: INIT → PUBLISHED 시도
        int updated = outboxRepository.markAsPublishedIfInit(
                outbox.getId(),
                LocalDateTime.now()
        );

        // then: 업데이트 실패 (경쟁 상태 방지)
        assertThat(updated).isEqualTo(0);
    }

    @Test
    @DisplayName("실패 처리 : SEND_FAIL로 변경 및 재시도 횟수 증가")
    void markAsFailedAndIncrementRetry() {
        // given
        Outbox outbox = OutboxFixture.memberJoinInit(1L);
        outboxRepository.save(outbox);
        em.flush();
        em.clear();

        int initialRetryCount = outbox.getRetryCount();

        // when
        int updated = outboxRepository.markAsFailedAndIncrementRetry(outbox.getId());
        em.flush();
        em.clear();

        // then
        assertThat(updated).isEqualTo(1);

        Outbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(OutboxStatus.SEND_FAIL);
        assertThat(result.getRetryCount()).isEqualTo(initialRetryCount + 1);
    }

    @Test
    @DisplayName("발행 대기 이벤트 조회 : SEND_FAIL 상태만 조회")
    void findPendingEvents_SendFail() {
        // given : SEND_FAIL 5개, PUBLISHED 1개
        for (int i = 0; i < 5; i++) {
            Outbox fail = OutboxFixture.builder()
                    .eventType(EventType.MEMBER_JOIN)
                    .aggregateId((long) i)
                    .status(OutboxStatus.SEND_FAIL)
                    .build();

            outboxRepository.save(fail);
        }

        Outbox success = OutboxFixture.builder()
                .eventType(EventType.MEMBER_JOIN)
                .aggregateId(999L)
                .status(OutboxStatus.PUBLISHED)
                .build();

        outboxRepository.save(success);

        em.flush();
        em.clear();

        // when
        List<Outbox> result = outboxRepository.findPendingEvents(
                OutboxStatus.SEND_FAIL,
                OutboxStatus.INIT,
                LocalDateTime.now().minusMinutes(5),
                PageRequest.of(0, 100)
        );

        // then : SEND_FAIL 5개만 조회
        assertThat(result).hasSize(5);
        assertThat(result).allMatch(o -> o.getStatus() == OutboxStatus.SEND_FAIL);
    }

    @Test
    @DisplayName("발행 대기 이벤트 조회: 배치 사이즈 제한")
    void findPendingEvents_BatchLimit() {
        // given : SEND_FAIL 150개
        for (int i = 0; i < 150; i++) {
            Outbox fail = OutboxFixture.builder()
                    .eventType(EventType.MEMBER_JOIN)
                    .aggregateId((long) i)
                    .status(OutboxStatus.SEND_FAIL)
                    .build();

            outboxRepository.save(fail);
        }
        em.flush();
        em.clear();

        // when : 최대 100개만 조회
        List<Outbox> result = outboxRepository.findPendingEvents(
                OutboxStatus.SEND_FAIL,
                OutboxStatus.INIT,
                LocalDateTime.now().minusMinutes(5),
                PageRequest.of(0, 100)
        );

        // then : 100개만 조회 (OOM 방지)
        assertThat(result).hasSize(100);
    }

    @Test
    @DisplayName("발행 대기 이벤트 조회 : 최근 INIT은 제외 (5분 기준)")
    void findPendingEvents_ExcludeRecentInit() {
        // given : SEND_FAIL 2개 + 최근 INIT 1개
        Outbox fail = OutboxFixture.builder()
                .aggregateId(1L)
                .status(OutboxStatus.SEND_FAIL)
                .build();

        outboxRepository.save(fail);

        Outbox fail2 = OutboxFixture.builder()
                .aggregateId(2L)
                .status(OutboxStatus.SEND_FAIL)
                .build();

        outboxRepository.save(fail2);

        outboxRepository.save(OutboxFixture.memberJoinInit(3L));

        em.flush();
        em.clear();

        // when : 5분 이전 기준
        List<Outbox> result = outboxRepository.findPendingEvents(
                OutboxStatus.SEND_FAIL,
                OutboxStatus.INIT,
                LocalDateTime.now().minusMinutes(5),
                PageRequest.of(0, 100)
        );

        // then : SEND_FAIL 2개만 (최근 INIT 제외)
        assertThat(result).hasSize(2);
        assertThat(result).allMatch(o -> o.getStatus() == OutboxStatus.SEND_FAIL);
    }

    @Test
    @DisplayName("멱등성 : 동일 eventId 중복 저장 시 예외 발생")
    void preventDuplicateEventId_ThrowsException() {
        // given : 첫 번째 이벤트 저장
        String duplicateEventId = "duplicate-test-event-id";

        Outbox outbox1 = OutboxFixture.builder()
                .eventId(duplicateEventId)
                .eventType(EventType.MEMBER_JOIN)
                .aggregateId(1L)
                .build();

        outboxRepository.save(outbox1);
        em.flush();
        em.clear();

        // when & then : 같은 eventId로 저장 시도하면 예외 발생
        Outbox outbox2 = OutboxFixture.builder()
                .eventId(duplicateEventId)
                .eventType(EventType.MEMBER_JOIN)
                .aggregateId(1L)
                .build();

        assertThatThrownBy(() -> {
            outboxRepository.save(outbox2);
            em.flush();
        }).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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
        em.flush();
        em.clear();

        // then : 2개 모두 저장됨
        List<Outbox> result = outboxRepository.findAll();
        assertThat(result).hasSize(2);
    }
}