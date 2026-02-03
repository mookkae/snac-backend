package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * OutboxCleanupScheduler 단위 테스트
 * <p>
 * 배치 단위 삭제 로직 (Repository 직접 호출)
 * 배치 크기만큼 반복 삭제
 * 실패 시 롤백 없이 중단 및 로깅
 * retentionDays, batchSize 파라미터
 */
@ExtendWith(MockitoExtension.class)
class OutboxCleanupSchedulerTest {

    private OutboxCleanupScheduler scheduler;

    @Mock
    private OutboxRepository outboxRepository;

    private static final int RETENTION_DAYS = 30;
    private static final int BATCH_SIZE = 1000;

    @BeforeEach
    void setUp() {
        scheduler = new OutboxCleanupScheduler(
                outboxRepository,
                RETENTION_DAYS,
                BATCH_SIZE
        );
    }

    @Nested
    @DisplayName("cleanupOldPublishedEvents")
    class CleanupOldPublishedEventsTest {

        @Test
        @DisplayName("성공 : 삭제 대상 없음 -> 즉시 종료")
        void noEventsToDelete_immediateReturn() {
            // given
            given(outboxRepository.deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE)))
                    .willReturn(0);

            // when
            scheduler.cleanupOldPublishedEvents();

            // then : 1회만 호출
            verify(outboxRepository, times(1))
                    .deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE));
        }

        @Test
        @DisplayName("성공 : 배치 크기 미만 삭제 -> 1회 실행 후 종료")
        void lessThanBatchSize_singleBatch() {
            // given : 500건 삭제 (배치 크기 1000 미만)
            given(outboxRepository.deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE)))
                    .willReturn(500);

            // when
            scheduler.cleanupOldPublishedEvents();

            // then : 1회만 호출 (500 < 1000이므로 더 없다고 판단)
            verify(outboxRepository, times(1))
                    .deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE));
        }

        @Test
        @DisplayName("성공 : 배치 크기만큼 삭제 -> 반복 실행")
        void exactBatchSize_repeatUntilLess() {
            // given : 첫 번째 1000건, 두 번째 1000건, 세 번째 500건
            given(outboxRepository.deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE)))
                    .willReturn(1000)   // 배치 1: 1000건 -> 계속
                    .willReturn(1000)   // 배치 2: 1000건 -> 계속
                    .willReturn(500);   // 배치 3: 500건 -> 종료

            // when
            scheduler.cleanupOldPublishedEvents();

            // then : 3회 호출 (총 2500건 삭제)
            verify(outboxRepository, times(3))
                    .deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE));
        }

        @Test
        @DisplayName("성공 : 대량 데이터 삭제 -> 여러 배치 반복")
        void largeDataset_multipleBatches() {
            // given : 5배치 후 종료
            given(outboxRepository.deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE)))
                    .willReturn(1000)
                    .willReturn(1000)
                    .willReturn(1000)
                    .willReturn(1000)
                    .willReturn(0);     // 마지막: 0건

            // when
            scheduler.cleanupOldPublishedEvents();

            // then : 5회 호출
            verify(outboxRepository, times(5))
                    .deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE));
        }
    }

    @Nested
    @DisplayName("실패 시나리오 (트랜잭션은 Repository가 관리)")
    class FailureScenarioTest {

        @Test
        @DisplayName("실패 : 첫 배치 실패 -> 즉시 중단")
        void firstBatchFails_immediateStop() {
            // given : 첫 번째 배치에서 예외 발생
            given(outboxRepository.deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE)))
                    .willThrow(new RuntimeException("DB Connection Error"));

            // when
            scheduler.cleanupOldPublishedEvents();

            // then : 1회만 호출 (실패 후 중단)
            verify(outboxRepository, times(1))
                    .deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE));
        }

        @Test
        @DisplayName("실패 : 중간 배치 실패 -> 이전 배치는 커밋됨 (독립 트랜잭션)")
        void middleBatchFails_previousBatchesCommitted() {
            // given : 배치 1, 2 성공 -> 배치 3 실패
            given(outboxRepository.deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE)))
                    .willReturn(1000)   // 배치 1: 성공 (커밋)
                    .willReturn(1000)   // 배치 2: 성공 (커밋)
                    .willThrow(new RuntimeException("Deadlock detected"));  // 배치 3: 실패

            // when
            scheduler.cleanupOldPublishedEvents();

            // then : 3회 호출
            verify(outboxRepository, times(3))
                    .deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE));
            // 배치 1, 2는 이미 커밋됨 (Repository의 @Transactional)
            // 배치 3 실패해도 롤백 대상 없음 (스케줄러에 트랜잭션 없음)
        }
    }

    @Nested
    @DisplayName("파라미터 검증")
    class ParameterValidationTest {

        @Test
        @DisplayName("검증 : retentionDays 기준 threshold 계산")
        void thresholdCalculation_basedOnRetentionDays() {
            // given
            given(outboxRepository.deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE)))
                    .willReturn(0);

            // when
            scheduler.cleanupOldPublishedEvents();

            // then : 30일 전 기준
            verify(outboxRepository).deleteOldPublishedEvents(
                    argThat(threshold -> {
                        LocalDateTime expected = LocalDateTime.now().minusDays(RETENTION_DAYS);
                        // 1분 오차 허용
                        return threshold.isAfter(expected.minusMinutes(1))
                                && threshold.isBefore(expected.plusMinutes(1));
                    }),
                    eq(BATCH_SIZE)
            );
        }

        @Test
        @DisplayName("검증 : batchSize 파라미터 전달")
        void batchSizeParameter_passedCorrectly() {
            // given
            given(outboxRepository.deleteOldPublishedEvents(any(LocalDateTime.class), eq(BATCH_SIZE)))
                    .willReturn(0);

            // when
            scheduler.cleanupOldPublishedEvents();

            // then
            verify(outboxRepository).deleteOldPublishedEvents(
                    any(LocalDateTime.class),
                    eq(BATCH_SIZE)
            );
        }
    }
}
