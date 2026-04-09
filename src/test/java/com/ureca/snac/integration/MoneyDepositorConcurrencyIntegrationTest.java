package com.ureca.snac.integration;

import com.ureca.snac.money.service.MoneyDepositor;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.infra.fixture.TossResponseFixture;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.PaymentAlreadySuccessException;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 머니 입금 처리 동시성 및 데이터 정합성 검증 통합 테스트
 * 비관적 락(FOR UPDATE)을 통한 이중 충전 방지 로직을 검증한다.
 */
@DisplayName("MoneyDepositor 동시성 통합 테스트")
class MoneyDepositorConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MoneyDepositor moneyDepositor;

    @Test
    @DisplayName("동시성 검증: 동일한 결제에 대해 여러 스레드가 동시에 입금 처리를 시도할 때, 비관적 락에 의해 단 한 번만 성공해야 함")
    void deposit_ConcurrencyTest() throws InterruptedException {
        // given
        Member member = createMemberWithWallet("concurrency");
        Payment payment = paymentRepository.save(PaymentFixture.builder()
                .member(member)
                .orderId("order_concurrent_123")
                .amount(10000L)
                .status(PaymentStatus.PENDING)
                .build());

        TossConfirmResponse tossResponse = TossResponseFixture.createConfirmResponse("payment_key_concurrent");

        int threadCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // when
        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    moneyDepositor.deposit(payment, member, tossResponse);
                    successCount.incrementAndGet();
                } catch (PaymentAlreadySuccessException e) {
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Unexpected exception: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executorService.shutdown();

        // then
        // 1. 단 한 번만 성공해야 함
        assertThat(successCount.get()).isEqualTo(1);
        // 2. 나머지는 모두 중복 처리 예외가 발생해야 함 (비관적 락에 의해 직렬화됨)
        assertThat(failCount.get()).isEqualTo(threadCount - 1);

        // 3. 최종 DB 상태 검증
        Payment finalPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(finalPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        
        // 4. 지갑 잔액 검증 (10,000원 한 번만 입금되어야 함)
        Long balance = walletRepository.findByMemberId(member.getId()).orElseThrow().getMoneyBalance();
        assertThat(balance).isEqualTo(10000L);

        // 5. 충전 기록 검증 (1개만 생성되어야 함)
        long rechargeCount = moneyRechargeRepository.count();
        assertThat(rechargeCount).isEqualTo(1);
    }
}
