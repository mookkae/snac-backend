package com.ureca.snac.outbox.service;

import com.ureca.snac.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * OutboxStatusUpdater 단위 테스트
 * <p>
 * 원자적 상태 업데이트 검증
 * updated == 0 경쟁 상태(Race Condition) 처리 검증
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxStatusUpdater 테스트")
class OutboxStatusUpdaterTest {

    private OutboxStatusUpdater outboxStatusUpdater;

    @Mock
    private OutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        outboxStatusUpdater = new OutboxStatusUpdater(outboxRepository);
    }

    @Nested
    @DisplayName("markAsPublished 메서드")
    class MarkAsPublishedTest {

        @Test
        @DisplayName("성공 : 정상 업데이트 (updated == 1)")
        void markAsPublished_success() {
            // given
            Long outboxId = 1L;
            given(outboxRepository.markAsPublished(eq(outboxId), any(LocalDateTime.class)))
                    .willReturn(1);

            // when & then : 예외 없이 완료
            assertThatCode(() -> outboxStatusUpdater.markAsPublished(outboxId))
                    .doesNotThrowAnyException();

            verify(outboxRepository, times(1))
                    .markAsPublished(eq(outboxId), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("성공 : 경쟁 상태 - 이미 PUBLISHED (updated == 0)")
        void markAsPublished_alreadyPublished_gracefullyHandled() {
            // given : 다른 스레드가 먼저 PUBLISHED로 업데이트한 상황
            Long outboxId = 1L;
            given(outboxRepository.markAsPublished(eq(outboxId), any(LocalDateTime.class)))
                    .willReturn(0);

            // when & then : 예외 없이 정상 처리 (멱등성 보장)
            assertThatCode(() -> outboxStatusUpdater.markAsPublished(outboxId))
                    .doesNotThrowAnyException();

            verify(outboxRepository, times(1))
                    .markAsPublished(eq(outboxId), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("성공 : 경쟁 상태 - 존재하지 않는 Outbox (updated == 0)")
        void markAsPublished_notExists_gracefullyHandled() {
            // given : 존재하지 않는 outboxId
            Long nonExistentId = 999L;
            given(outboxRepository.markAsPublished(eq(nonExistentId), any(LocalDateTime.class)))
                    .willReturn(0);

            // when & then : 예외 없이 정상 처리
            assertThatCode(() -> outboxStatusUpdater.markAsPublished(nonExistentId))
                    .doesNotThrowAnyException();

            verify(outboxRepository, times(1))
                    .markAsPublished(eq(nonExistentId), any(LocalDateTime.class));
        }
    }

    @Nested
    @DisplayName("markAsFailed 메서드")
    class MarkAsFailedTest {

        @Test
        @DisplayName("성공 : 정상 업데이트 (updated == 1)")
        void markAsFailed_success() {
            // given
            Long outboxId = 1L;
            given(outboxRepository.markAsFailedAndIncrementRetry(outboxId))
                    .willReturn(1);

            // when & then : 예외 없이 완료
            assertThatCode(() -> outboxStatusUpdater.markAsFailed(outboxId))
                    .doesNotThrowAnyException();

            verify(outboxRepository, times(1))
                    .markAsFailedAndIncrementRetry(outboxId);
        }

        @Test
        @DisplayName("성공 : 경쟁 상태 - 이미 PUBLISHED (updated == 0)")
        void markAsFailed_alreadyPublished_gracefullyHandled() {
            // given : 다른 스레드가 먼저 PUBLISHED로 업데이트한 상황
            // markAsFailed는 PUBLISHED 상태를 제외하므로 updated == 0
            Long outboxId = 1L;
            given(outboxRepository.markAsFailedAndIncrementRetry(outboxId))
                    .willReturn(0);

            // when & then : 예외 없이 정상 처리 (멱등성 보장)
            assertThatCode(() -> outboxStatusUpdater.markAsFailed(outboxId))
                    .doesNotThrowAnyException();

            verify(outboxRepository, times(1))
                    .markAsFailedAndIncrementRetry(outboxId);
        }

        @Test
        @DisplayName("성공 : 경쟁 상태 - 존재하지 않는 Outbox (updated == 0)")
        void markAsFailed_notExists_gracefullyHandled() {
            // given : 존재하지 않는 outboxId
            Long nonExistentId = 999L;
            given(outboxRepository.markAsFailedAndIncrementRetry(nonExistentId))
                    .willReturn(0);

            // when & then : 예외 없이 정상 처리
            assertThatCode(() -> outboxStatusUpdater.markAsFailed(nonExistentId))
                    .doesNotThrowAnyException();

            verify(outboxRepository, times(1))
                    .markAsFailedAndIncrementRetry(nonExistentId);
        }
    }
}
