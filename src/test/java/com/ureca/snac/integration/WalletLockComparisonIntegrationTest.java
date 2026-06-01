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
 * 락 전략 동시성 비교 통합 테스트
 * <p>
 * 시나리오: 리스너(보너스 지급) · 충전 API · 스케줄러(에스크로 환불)가
 * 동시에 같은 지갑을 갱신하는 실제 경합 구조를 재현
 * 동일 지갑 행에 대한 동시성 read-modify-write로 잔액 유실을 재현
 * <p>
 * 브랜치별 구현
 * experiment/jvm-lock         : SynchronizedWalletFacade (per-member ReentrantLock+ Facade)
 * experiment/cas              : Atomic SQL UPDATE
 * experiment/optimistic-lock  : @Version + @Retryable
 * experiment/pessimistic-lock : pessimistic lock (FOR UPDATE)
 */
@DisplayName("지갑 락 전략 동시성 비교 통합 테스트")
class WalletLockComparisonIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private WalletService walletService;

    private Member member;

    private static final int GROUP_SIZE = 30;   // 그룹당 스레드 수 (총 90개)
    private static final long AMOUNT = 1000L;

    @BeforeEach
    void setUp() {
        member = createMemberWithWallet("lock_cmp_");

        // 스케줄러(Group C)가 환불할 에스크로 사전 적립
        walletService.depositMoney(member.getId(), GROUP_SIZE * AMOUNT);
        walletService.moveCompositeToEscrow(member.getId(), GROUP_SIZE * AMOUNT, 0);
    }

    @Test
    @DisplayName("리스너·충전 API·스케줄러 동시 접근 → 잔액 정합성 보장")
    void concurrentMultiPath_balanceConsistent() throws InterruptedException {

        AtomicInteger successA = new AtomicInteger(); // 리스너
        AtomicInteger successB = new AtomicInteger(); // 충전 API
        AtomicInteger successC = new AtomicInteger(); // 스케줄러
        AtomicInteger failCount = new AtomicInteger();
        ConcurrentLinkedQueue<Long> elapsedTimes = new ConcurrentLinkedQueue<>();

        int totalThreads = GROUP_SIZE * 3;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);

        // 모든 스레드가 준비 완료됐음을 메인 스레드에 알리는 latch
        CountDownLatch ready = new CountDownLatch(totalThreads);
        // 메인 스레드가 전원 준비 확인 후 동시 출발시키는 latch
        CountDownLatch start = new CountDownLatch(1);

        List<Future<?>> futures = new ArrayList<>();

        // Group A: 리스너 — depositPoint
        for (int i = 0; i < GROUP_SIZE; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long begin = System.currentTimeMillis();
                try {
                    walletService.depositPoint(member.getId(), AMOUNT);
                    successA.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    elapsedTimes.add(System.currentTimeMillis() - begin);
                }
            }));
        }

        // Group B: 충전 API — depositMoney
        for (int i = 0; i < GROUP_SIZE; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long begin = System.currentTimeMillis();
                try {
                    walletService.depositMoney(member.getId(), AMOUNT);
                    successB.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    elapsedTimes.add(System.currentTimeMillis() - begin);
                }
            }));
        }

        // Group C: 스케줄러 — cancelCompositeEscrow (에스크로 환불)
        for (int i = 0; i < GROUP_SIZE; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long begin = System.currentTimeMillis();
                try {
                    walletService.cancelCompositeEscrow(member.getId(), AMOUNT, 0);
                    successC.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    elapsedTimes.add(System.currentTimeMillis() - begin);
                }
            }));
        }

        // 전원 준비 완료 대기 후 동시 출발
        ready.await();
        start.countDown();

        for (Future<?> future : futures) {
            try {
                future.get(120, TimeUnit.SECONDS);
            } catch (ExecutionException ignored) {
            } catch (TimeoutException e) {
                fail("태스크가 120초 내 완료되지 않음: " + e.getMessage());
            }
        }

        executor.shutdown();
        executor.awaitTermination(130, TimeUnit.SECONDS);

        LongSummaryStatistics stats = elapsedTimes.stream()
                .mapToLong(Long::longValue)
                .summaryStatistics();

        Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();

        printResult(successA.get(), successB.get(), successC.get(), failCount.get(), stats, wallet);

        // 정합성 검증: 성공한 만큼만 정확히 반영됐는가
        long expectedMoney = (successB.get() * AMOUNT) + (successC.get() * AMOUNT);
        long expectedPoint = successA.get() * AMOUNT;
        long expectedEscrow = (GROUP_SIZE - successC.get()) * AMOUNT;

        assertThat(wallet.getMoneyBalance())
                .as("머니 잔액 = 충전 성공분 + 에스크로 환불 성공분")
                .isEqualTo(expectedMoney);

        assertThat(wallet.getPointBalance())
                .as("포인트 잔액 = 보너스 지급 성공분")
                .isEqualTo(expectedPoint);

        assertThat(wallet.getMoneyEscrow())
                .as("에스크로 = 초기 적립 - 환불 성공분")
                .isEqualTo(expectedEscrow);
    }

    private void printResult(int successA, int successB, int successC, int failCount,
                             LongSummaryStatistics stats, Wallet wallet) {
        System.out.println("\n========== 락 전략 비교 결과 ==========");
        System.out.printf("리스너   (depositPoint)            : %d / %d 성공%n", successA, GROUP_SIZE);
        System.out.printf("충전 API  (depositMoney)           : %d / %d 성공%n", successB, GROUP_SIZE);
        System.out.printf("스케줄러  (cancelCompositeEscrow)  : %d / %d 성공%n", successC, GROUP_SIZE);
        System.out.printf("실패 건수                       : %d%n", failCount);
        System.out.printf("머니 잔액                       : %,d원%n", wallet.getMoneyBalance());
        System.out.printf("포인트 잔액                     : %,d원%n", wallet.getPointBalance());
        System.out.printf("에스크로 잔액                   : %,d원%n", wallet.getMoneyEscrow());
        System.out.printf("응답시간(ms)                    : min=%-4d  max=%-4d  avg=%.1f%n",
                stats.getMin(), stats.getMax(), stats.getAverage());
        System.out.println("=========================================\n");
    }
}
