package com.ureca.snac.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 즉시 발행 스레드풀 넘침 단위 테스트
 * <p>
 * 대기줄이 넘친 작업을 요청 스레드가 떠맡으면, 커넥션을 쥔 채 두 번째 커넥션을 기다리는 교착이 생긴다.
 * 넘친 작업은 버려지고 outbox 행은 INIT으로 남아 폴러가 발행한다
 */
class EventExecutorOverflowTest {

    @Test
    @DisplayName("스레드풀이 가득 차면 넘친 작업은 호출한 스레드에서 실행되지 않고 버려진다")
    void overflowTaskIsDiscarded() throws InterruptedException {
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) new AsyncConfig().eventExecutor();
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        int maxThreads = pool.getMaximumPoolSize();
        int queueCapacity = pool.getQueue().remainingCapacity();

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(maxThreads);
        AtomicBoolean overflowRan = new AtomicBoolean(false);

        try {
            // given: 실행 스레드와 대기열을 모두 채운다
            for (int i = 0; i < maxThreads + queueCapacity; i++) {
                executor.execute(() -> {
                    running.countDown();
                    awaitQuietly(release);
                });
            }
            assertThat(running.await(5, SECONDS)).isTrue();
            assertThat(pool.getPoolSize()).isEqualTo(maxThreads);
            assertThat(pool.getQueue().remainingCapacity()).isZero();

            // when: 작업을 하나 더 넣는다
            executor.execute(() -> overflowRan.set(true));

            // then: 호출한 스레드에서 바로 실행되지 않는다
            assertThat(overflowRan).isFalse();
        } finally {
            release.countDown();
            pool.shutdown();
        }

        boolean terminated = pool.awaitTermination(10, SECONDS);
        if (!terminated) {
            pool.shutdownNow();
        }
        assertThat(terminated).isTrue();

        // then: 남은 작업이 모두 끝난 뒤에도 실행되지 않았다 = 버려졌다
        assertThat(overflowRan).isFalse();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
