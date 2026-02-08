package com.ureca.snac.integration;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.TransactionCategory;
import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.fixture.TossResponseFixture;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.money.entity.MoneyRecharge;
import com.ureca.snac.money.service.MoneyService;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentMethod;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
import com.ureca.snac.payment.service.PaymentInternalService;
import com.ureca.snac.payment.service.PaymentService;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.PaymentCancelResponseFixture;
import com.ureca.snac.wallet.entity.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;

/**
 * 결제 동시성 통합 테스트
 * Pessimistic Lock, Race Condition 검증
 */
@DisplayName("결제 동시성 통합 테스트")
class PaymentConcurrencyIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MoneyService moneyService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentInternalService paymentInternalService;

    @MockitoBean
    private PaymentGatewayAdapter paymentGatewayAdapter;

    private Member member;

    private static final Long RECHARGE_AMOUNT = 10000L;
    private static final int THREAD_COUNT = 5;

    @BeforeEach
    void setUpMember() {
        member = createMemberWithWallet("conc_");
    }

    @Nested
    @DisplayName("결제 승인")
    class ConfirmConcurrencyTest {

        @Test
        @DisplayName("동시성 : 동일 orderId N건 동시 승인 → Wallet 1회만 증가")
        void shouldAllowOnlyOneConfirmation() throws InterruptedException {
            // given
            MoneyRechargePreparedResponse prepared = prepareRecharge();
            String paymentKey = "conc_pk_" + System.currentTimeMillis();
            mockTossConfirm(paymentKey);

            // when: N건 동시 승인
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failCount = new AtomicInteger();

            runConcurrently(() -> {
                try {
                    moneyService.processRechargeSuccess(
                            paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            }, THREAD_COUNT);

            // then: Wallet 잔액 1회만 증가 (핵심 불변식)
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isEqualTo(RECHARGE_AMOUNT);

            // MoneyRecharge 1건만 생성
            List<MoneyRecharge> recharges = moneyRechargeRepository.findAll();
            assertThat(recharges).hasSize(1);

            // 전체 = 성공 + 실패 (deposit이 멱등하게 early return하므로 실패 0건일 수 있음)
            assertThat(successCount.get() + failCount.get()).isEqualTo(THREAD_COUNT);
        }
    }

    @Nested
    @DisplayName("결제 취소")
    class CancelConcurrencyTest {

        @Test
        @DisplayName("동시성 : 동일 paymentKey N건 동시 취소 → Wallet 출금 1회만")
        void shouldAllowOnlyOneCancellation() throws InterruptedException {
            // given: 충전 완료
            String paymentKey = "conc_cancel_pk_" + System.currentTimeMillis();
            prepareAndCompleteRecharge(paymentKey);
            mockTossCancel(paymentKey);

            // when: N건 동시 취소
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failCount = new AtomicInteger();

            runConcurrently(() -> {
                try {
                    paymentService.cancelPayment(paymentKey, "동시 취소 테스트", member.getEmail());
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            }, THREAD_COUNT);

            // then: Wallet 잔액 0 (1회만 출금)
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isZero();

            // AssetHistory CANCEL 1건
            List<AssetHistory> cancelHistories = assetHistoryRepository.findAll().stream()
                    .filter(h -> h.getCategory() == TransactionCategory.RECHARGE_CANCEL)
                    .toList();
            assertThat(cancelHistories).hasSize(1);
        }
    }

    @Nested
    @DisplayName("보상 처리")
    class CompensationConcurrencyTest {

        @Test
        @DisplayName("동시성 : processCompensation N건 동시 호출 → Wallet 출금 1회만")
        void shouldAllowOnlyOneCompensation() throws InterruptedException {
            // given: 충전 완료된 결제를 CANCELED 상태로 변경 (토스 취소 성공 상태 시뮬레이션)
            String paymentKey = "conc_comp_pk_" + System.currentTimeMillis();
            MoneyRechargePreparedResponse prepared = prepareRecharge();
            mockTossConfirm(paymentKey);
            moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());

            Payment payment = paymentRepository.findByPaymentKeyWithMember(paymentKey).orElseThrow();
            payment.cancel("보상 테스트용 취소");
            paymentRepository.saveAndFlush(payment);

            PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                    payment.getId(), member.getId(), RECHARGE_AMOUNT, "보상 테스트", OffsetDateTime.now());

            // when: N건 동시 보상 처리
            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failCount = new AtomicInteger();

            runConcurrently(() -> {
                try {
                    paymentInternalService.processCompensation(event);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            }, THREAD_COUNT);

            // then: compensationCompleted = true
            Payment result = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(result.isCompensationCompleted()).isTrue();

            // AssetHistory CANCEL 1건만
            List<AssetHistory> cancelHistories = assetHistoryRepository.findAll().stream()
                    .filter(h -> h.getCategory() == TransactionCategory.RECHARGE_CANCEL)
                    .toList();
            assertThat(cancelHistories).hasSize(1);
        }
    }

    // ================= Helper ====================

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
                future.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException ignored) {
            }
        }

        executor.shutdown();
        executor.awaitTermination(15, TimeUnit.SECONDS);
    }

    private MoneyRechargePreparedResponse prepareRecharge() {
        return moneyService.prepareRecharge(new MoneyRechargeRequest(RECHARGE_AMOUNT), member.getEmail());
    }

    private void prepareAndCompleteRecharge(String paymentKey) {
        MoneyRechargePreparedResponse prepared = prepareRecharge();
        mockTossConfirm(paymentKey);
        moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());
    }

    private void mockTossConfirm(String paymentKey) {
        given(paymentGatewayAdapter.confirmPayment(anyString(), anyString(), anyLong()))
                .willReturn(TossResponseFixture.createConfirmResponse(paymentKey));
    }

    private void mockTossCancel(String paymentKey) {
        given(paymentGatewayAdapter.cancelPayment(anyString(), anyString()))
                .willReturn(PaymentCancelResponseFixture.create(paymentKey, RECHARGE_AMOUNT, "동시 취소 테스트"));
    }
}
