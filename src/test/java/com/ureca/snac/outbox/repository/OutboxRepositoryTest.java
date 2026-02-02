package com.ureca.snac.outbox.repository;

import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.fixture.OutboxFixture;
import com.ureca.snac.support.RepositoryTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
@DisplayName("OutboxRepository 테스트")
@Transactional
class OutboxRepositoryTest extends RepositoryTestSupport {

    @Autowired
    private OutboxRepository outboxRepository;

    @Nested
    @DisplayName("markAsPublished 메서드")
    class MarkAsPublishedTest {

        @Test
        @DisplayName("성공 : INIT -> PUBLISHED 원자적 업데이트")
        void markAsPublished_fromInit_update_Success() {
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
        void markAsPublished_fromSendFail_update_Success() {
            // given
            Outbox outbox = OutboxFixture.failedWithRetry(1L, 1);
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
        @DisplayName("실패 : 이미 PUBLISHED 상태는 업데이트 실패")
        void markAsPublished_alreadyPublished_updateFails() {
            // given
            Outbox outbox = OutboxFixture.publishedOld(1L, 0);

            outboxRepository.save(outbox);
            outboxRepository.flush();

            // when
            int updated = outboxRepository.markAsPublished(
                    outbox.getId(),
                    LocalDateTime.now()
            );

            // then
            assertThat(updated).isEqualTo(0);
        }

        @Test
        @DisplayName("실패 : 존재하지 않는 Outbox는 업데이트 실패")
        void markAsPublished_notExists_updateFails() {
            // when
            int updated = outboxRepository.markAsPublished(
                    999L,
                    LocalDateTime.now()
            );

            // then
            assertThat(updated).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("markAsFailedAndIncrementRetry 메서드")
    class MarkAsFailedAndIncrementRetryTest {

        @Test
        @DisplayName("성공 : INIT -> SEND_FAIL + retryCount 증가")
        void markAsFailed_fromInit_updatesAndIncrementsRetry() {
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
        @DisplayName("성공 : SEND_FAIL -> SEND_FAIL + retryCount 증가")
        void markAsFailed_fromSendFail_incrementsRetry() {
            // given
            Outbox outbox = OutboxFixture.failedWithRetry(1L, 1);
            outboxRepository.save(outbox);
            outboxRepository.flush();

            // when
            int updated = outboxRepository.markAsFailedAndIncrementRetry(outbox.getId());
            outboxRepository.flush();

            em.clear();

            // then
            assertThat(updated).isEqualTo(1);

            Outbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(OutboxStatus.SEND_FAIL);
            assertThat(result.getRetryCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("실패 : PUBLISHED 상태는 실패 처리 안 됨")
        void markAsFailed_alreadyPublished_noUpdate() {
            // given
            Outbox outbox = OutboxFixture.publishedOld(1L, 0);

            outboxRepository.save(outbox);
            outboxRepository.flush();

            // when
            int updated = outboxRepository.markAsFailedAndIncrementRetry(outbox.getId());
            outboxRepository.flush();

            em.clear();

            // then
            assertThat(updated).isEqualTo(0);

            Outbox result = outboxRepository.findById(outbox.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(result.getRetryCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("findPendingEvents 메서드")
    class FindPendingEventsTest {

        @Test
        @DisplayName("성공 : SEND_FAIL 상태만 조회 (maxRetry 이내)")
        void findPendingEvents_sendFailWithinMaxRetry_returnsEligibleEvents() {
            // given
            outboxRepository.save(OutboxFixture.failedWithRetry(1L, 0));
            outboxRepository.save(OutboxFixture.failedWithRetry(2L, 1));
            outboxRepository.save(OutboxFixture.failedWithRetry(3L, 2));
            outboxRepository.save(OutboxFixture.failedWithRetry(4L, 3));

            outboxRepository.flush();

            // when
            List<Outbox> result = outboxRepository.findPendingEvents(
                    OutboxStatus.SEND_FAIL,
                    OutboxStatus.INIT,
                    LocalDateTime.now().minusMinutes(5),
                    3,
                    PageRequest.of(0, 100)
            );

            // then
            assertThat(result).hasSize(3);
            assertThat(result).allMatch(o -> o.getRetryCount() < 3);
        }

        @Test
        @DisplayName("성공 : 오래된 INIT 상태 포함 조회")
        void findPendingEvents_includesStaleInit_returnsMatchingEvents() {
            // given
            outboxRepository.save(OutboxFixture.failedWithRetry(1L, 1));
            outboxRepository.save(OutboxFixture.failedWithRetry(2L, 2));

            insertStaleInit(3L, 10);
            insertStaleInit(4L, 1);

            outboxRepository.flush();

            // when
            List<Outbox> result = outboxRepository.findPendingEvents(
                    OutboxStatus.SEND_FAIL,
                    OutboxStatus.INIT,
                    LocalDateTime.now().minusMinutes(5),
                    3,
                    PageRequest.of(0, 100)
            );

            // then
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
        void findPendingEvents_batchLimit_returnsLimitedResults() {
            // given
            for (int i = 0; i < 150; i++) {
                outboxRepository.save(OutboxFixture.failedWithRetry((long) i, 0));
            }
            outboxRepository.flush();

            // when
            List<Outbox> result = outboxRepository.findPendingEvents(
                    OutboxStatus.SEND_FAIL,
                    OutboxStatus.INIT,
                    LocalDateTime.now().minusMinutes(5),
                    3,
                    PageRequest.of(0, 100)
            );

            // then
            assertThat(result).hasSize(100);
        }

        @Test
        @DisplayName("성공 : PUBLISHED 상태는 제외")
        void findPendingEvents_excludesPublished_returnsOnlyPending() {
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

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo(OutboxStatus.SEND_FAIL);
        }
    }

    @Nested
    @DisplayName("멱등성 검증")
    class IdempotencyTest {

        @Test
        @DisplayName("멱등성 : 동일 eventId 중복 저장 시 예외 발생")
        void save_duplicateEventId_throwsException() {
            // given
            String duplicateEventId = "duplicate-test-event-id";

            Outbox outbox1 = OutboxFixture.builder()
                    .eventId(duplicateEventId)
                    .aggregateId(1L)
                    .build();

            outboxRepository.save(outbox1);
            outboxRepository.flush();

            // when & then
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
        @DisplayName("성공 : 다른 eventId는 정상 저장")
        void save_differentEventId_savesSuccessfully() {
            // given
            Outbox outbox1 = OutboxFixture.memberJoinInit(1L);
            Outbox outbox2 = OutboxFixture.memberJoinInit(2L);

            assertThat(outbox1.getEventId()).isNotEqualTo(outbox2.getEventId());

            // when
            outboxRepository.save(outbox1);
            outboxRepository.save(outbox2);
            outboxRepository.flush();

            // then
            List<Outbox> result = outboxRepository.findAll();
            assertThat(result).hasSize(2);
        }
    }

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
