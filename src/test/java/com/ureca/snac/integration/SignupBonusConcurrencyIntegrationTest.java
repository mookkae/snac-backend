package com.ureca.snac.integration;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.wallet.entity.Wallet;
import com.ureca.snac.wallet.service.SignupBonusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("회원가입 보너스 멱등성 동시성 통합 테스트")
class SignupBonusConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private SignupBonusService signupBonusService;

    private static final int THREAD_COUNT = 50;

    @Test
    @DisplayName("동시성: 동일 memberId N건 동시 지급 -> Wallet 포인트 1회만 증가, AssetHistory 1건")
    void shouldGrantBonusOnlyOnce() throws InterruptedException {
        // given
        Member member = createMemberWithWallet("bonus_");

        // when: 50스레드 동시 호출
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger duplicateCount = new AtomicInteger();

        runConcurrently(() -> {
            try {
                signupBonusService.grantSignupBonus(member.getId());
                successCount.incrementAndGet();
            } catch (DataIntegrityViolationException e) {
                duplicateCount.incrementAndGet();
            }
        }, THREAD_COUNT);

        // then
        // 1. Wallet 포인트 1000 (1회만 입금)
        Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(wallet.getPointBalance()).isEqualTo(1000L);

        // 2. AssetHistory 1건만 (idempotencyKey unique 보장)
        List<AssetHistory> histories = assetHistoryRepository.findAll();
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getIdempotencyKey())
                .isEqualTo("SIGNUP_BONUS:" + member.getId());

        // 3. 전체 = 성공 + 중복 (예외 누출 없음)
        assertThat(successCount.get() + duplicateCount.get()).isEqualTo(THREAD_COUNT);
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1);
    }

    private void runConcurrently(Runnable task, int threadCount) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        latch.countDown();

        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException ignored) {
            }
        }

        executor.shutdown();
        executor.awaitTermination(45, TimeUnit.SECONDS);
    }
}
