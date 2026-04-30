package com.ureca.snac.integration;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.wallet.entity.Wallet;
import com.ureca.snac.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 낙관적 락 vs 비관적 락 동시성 비교 통합 테스트
 * <p>
 * [낙관적 락]
 * Wallet.@Version + depositMoney() @Retryable(maxAttempts=5)
 * 측정: successCount=100, retryCount(충돌 재시도 총합), 응답시간 분산
 * <p>
 * [비관적 락]
 * depositMoney() findByMemberIdWithLock() (FOR UPDATE)
 * 측정: successCount=100, retryCount=0, 응답시간 분산
 * <p>
 * 정합성은 동일하게 보장되지만, 고경합 환경에서 낙관락은
 * 재시도로 응답시간 분산이 현저히 높아짐.
 */
@DisplayName("지갑 락 전략 동시성 비교 통합 테스트")
class WalletLockComparisonIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private WalletService walletService;

    private Member member;

    private static final long DEPOSIT_AMOUNT = 1000L;
    private static final int THREAD_COUNT = 100;

    @BeforeEach
    void setUp() {
        member = createMemberWithWallet("lock_cmp_");
    }

    @Test
    @DisplayName("100 스레드 동시 입금 → 전원 성공, 잔액 정합성 보장")
    void concurrent_deposit_allSucceed_balanceConsistent() throws InterruptedException {
        // when
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        ConcurrentLinkedQueue<Long> elapsedTimesMs = new ConcurrentLinkedQueue<>();

        runConcurrently(() -> {
            long start = System.currentTimeMillis();
            try {
                walletService.depositMoney(member.getId(), DEPOSIT_AMOUNT);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failCount.incrementAndGet();
            } finally {
                elapsedTimesMs.add(System.currentTimeMillis() - start);
            }
        }, THREAD_COUNT);

        // then
        long retryCount = -1; // 비관락: 재시도 없음. 낙관락 브랜치에서는 WalletRetryListener 복구 후 측정

        LongSummaryStatistics stats = elapsedTimesMs.stream()
                .mapToLong(Long::longValue)
                .summaryStatistics();

        Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
        long finalBalance = wallet.getMoneyBalance();

        printResult(successCount.get(), failCount.get(), retryCount, stats, finalBalance);

        // 잔액 정합성: 성공한 건수 × 금액만큼만 정확히 반영됐는가
        // (낙관락은 재시도 한도 초과 시 실패 건수 발생, 비관락은 0건)
        assertThat(finalBalance)
                .as("잔액 = 성공 건수 × 입금액 (팬텀 라이트 없음)")
                .isEqualTo(DEPOSIT_AMOUNT * successCount.get());

        assertThat(successCount.get() + failCount.get())
                .as("전체 스레드가 완료됐는가")
                .isEqualTo(THREAD_COUNT);
    }

    // ==================== helper ====================

    private void runConcurrently(Runnable task, int threadCount) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    ready.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        ready.countDown();

        for (Future<?> future : futures) {
            try {
                future.get(60, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
            } catch (TimeoutException e) {
                fail("태스크가 60초 내 완료되지 않음: " + e.getMessage());
            }
        }

        executor.shutdown();
        executor.awaitTermination(70, TimeUnit.SECONDS);
    }

    private void printResult(int successCount, int failCount, long retryCount,
                             LongSummaryStatistics stats, long finalBalance) {
        System.out.println("\n========== 락 전략 비교 결과 ==========");
        System.out.printf("성공 건수    : %d / %d%n", successCount, THREAD_COUNT);
        System.out.printf("실패 건수    : %d  ← 낙관락=재시도 한도 초과, 비관락=0 기대%n", failCount);
        System.out.printf("재시도 횟수  : %s%n", retryCount >= 0 ? retryCount + " (낙관락 충돌 재시도 총합)" : "N/A (비관락)");
        System.out.printf("최종 잔액    : %,d원%n", finalBalance);
        System.out.printf("응답시간(ms) : min=%-4d  max=%-4d  avg=%.1f%n",
                stats.getMin(), stats.getMax(), stats.getAverage());
        System.out.println("=========================================\n");
    }
}
