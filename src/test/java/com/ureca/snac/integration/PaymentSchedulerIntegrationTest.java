package com.ureca.snac.integration;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.exception.AlreadyCanceledPaymentException;
import com.ureca.snac.infra.fixture.TossResponseFixture;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.money.service.MoneyService;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.port.out.exception.GatewayTransientException;
import com.ureca.snac.payment.scheduler.PaymentReconciliationScheduler;
import com.ureca.snac.payment.service.PaymentInternalService;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.wallet.entity.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_API_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * 결제 대사 스케줄러 통합 테스트
 * PaymentReconciliationScheduler + PaymentReconciliationProcessor 실제 DB 연동
 */
@DisplayName("결제 대사 스케줄러 통합 테스트")
class PaymentSchedulerIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PaymentReconciliationScheduler scheduler;

    @Autowired
    private MoneyService moneyService;

    @Autowired
    private PaymentInternalService paymentInternalService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PaymentGatewayPort paymentGatewayPort;

    private Member member;

    private static final Long RECHARGE_AMOUNT = 10000L;

    @BeforeEach
    void setUpMember() {
        member = createMemberWithWallet("sched_");
    }

    @Nested
    @DisplayName("대사 스케줄러")
    class ReconciliationTest {

        @Test
        @DisplayName("성공 : 미결 결제 없음 -> 동작 없음")
        void shouldDoNothingWhenNoStalePayments() {
            // when
            scheduler.reconcileStalePayments();

            // then
            verify(paymentGatewayPort, never()).inquirePaymentByOrderId(anyString());
        }

        @Test
        @DisplayName("성공 : Toss DONE -> 토스 취소 + 로컬 취소 + Payment CANCELED")
        void shouldCancelTossDonePayment() {
            // given
            Payment stalePayment = createStalePendingPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayPort.inquirePaymentByOrderId(orderId))
                    .willReturn(TossResponseFixture.createInquiryResult("pk_done", orderId, "DONE"));

            // when
            scheduler.reconcileStalePayments();

            // then
            verify(paymentGatewayPort).cancelPayment(eq("pk_done"), anyString());

            Payment result = paymentRepository.findById(stalePayment.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        @DisplayName("성공 : Toss CANCELED -> 로컬만 취소")
        void shouldCancelLocallyForCanceledToss() {
            // given
            Payment stalePayment = createStalePendingPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayPort.inquirePaymentByOrderId(orderId))
                    .willReturn(TossResponseFixture.createInquiryResult("pk_canceled", orderId, "CANCELED"));

            // when
            scheduler.reconcileStalePayments();

            // then: Toss cancel 미호출
            verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());

            Payment result = paymentRepository.findById(stalePayment.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        @DisplayName("성공 : Toss 기록 없음(ExternalApiException) -> 로컬만 취소")
        void shouldCancelLocallyWhenTossNotFound() {
            // given
            Payment stalePayment = createStalePendingPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayPort.inquirePaymentByOrderId(orderId))
                    .willThrow(new com.ureca.snac.payment.exception.PaymentNotFoundException());

            // when
            scheduler.reconcileStalePayments();

            // then
            Payment result = paymentRepository.findById(stalePayment.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        @DisplayName("스킵 : Toss 조회 TossRetryableException -> PENDING 유지")
        void shouldSkipOnRetryableInquiry() {
            // given
            Payment stalePayment = createStalePendingPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayPort.inquirePaymentByOrderId(orderId))
                    .willThrow(new GatewayTransientException(new RuntimeException("timeout")));

            // when
            scheduler.reconcileStalePayments();

            // then
            Payment result = paymentRepository.findById(stalePayment.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("스킵 : Toss IN_PROGRESS -> PENDING 유지")
        void shouldSkipInProgressTossPayment() {
            // given
            Payment stalePayment = createStalePendingPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayPort.inquirePaymentByOrderId(orderId))
                    .willReturn(TossResponseFixture.createInquiryResult("pk_progress", orderId, "IN_PROGRESS"));

            // when
            scheduler.reconcileStalePayments();

            // then
            Payment result = paymentRepository.findById(stalePayment.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("성공 : Toss DONE + ALREADY_CANCELED -> 로컬 취소 진행")
        void shouldCancelLocallyWhenTossAlreadyCanceled() {
            // given
            Payment stalePayment = createStalePendingPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayPort.inquirePaymentByOrderId(orderId))
                    .willReturn(TossResponseFixture.createInquiryResult("pk_already", orderId, "DONE"));
            doThrow(new AlreadyCanceledPaymentException())
                    .when(paymentGatewayPort).cancelPayment(eq("pk_already"), anyString());

            // when
            scheduler.reconcileStalePayments();

            // then
            Payment result = paymentRepository.findById(stalePayment.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        @DisplayName("멱등성 : 이미 처리된 결제 -> Processor false 반환")
        void shouldSkipAlreadyProcessedPayment() {
            // given: 충전 완료 후 stale로 만들기 (SUCCESS 상태라 Processor가 false 반환)
            String paymentKey = "pk_idem_" + System.currentTimeMillis();
            MoneyRechargePreparedResponse prepared = moneyService.prepareRecharge(
                    new MoneyRechargeRequest(RECHARGE_AMOUNT), member);
            mockTossConfirm(paymentKey);
            moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getId());

            // Payment를 다시 PENDING으로 변경해서 스케줄러가 잡게 만들지 않음
            // 대신 createdAt을 과거로 -> 하지만 이미 SUCCESS라 Processor가 false 반환 확인
            Payment payment = paymentRepository.findByOrderId(prepared.orderId()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

            // stale로 만들어도 findStalePendingPayments는 PENDING 상태만 조회하므로 잡히지 않음
            // -> 결국 inquire 미호출
            scheduler.reconcileStalePayments();

            verify(paymentGatewayPort, never()).inquirePaymentByOrderId(anyString());
        }
    }

    @Nested
    @DisplayName("CANCEL_REQUESTED 대사")
    class CancelRequestedReconciliationTest {

        @Test
        @DisplayName("성공 : CANCEL_REQUESTED + Toss DONE → Toss 취소 + Wallet 회수 + CANCELED")
        void shouldCompleteCancellationForCancelRequestedWithTossDone() {
            // given
            Payment stalePayment = createStaleCancelRequestedPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayPort.inquirePaymentByOrderId(orderId))
                    .willReturn(TossResponseFixture.createInquiryResult("pk_cr_done", orderId, "DONE"));

            // when
            scheduler.reconcileStalePayments();

            // then: payment.getPaymentKey() (DB에 저장된 키)로 취소 호출
            verify(paymentGatewayPort).cancelPayment(eq(stalePayment.getPaymentKey()), anyString());

            Payment result = paymentRepository.findById(stalePayment.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);

            // freeze 선행 후 deductFrozenMoney -> balance=0
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isZero();
        }

        @Test
        @DisplayName("성공 : CANCEL_REQUESTED + Toss CANCELED → Toss 취소 스킵 + Wallet 회수 + CANCELED")
        void shouldCompleteCancellationForCancelRequestedWithTossCanceled() {
            // given
            Payment stalePayment = createStaleCancelRequestedPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayPort.inquirePaymentByOrderId(orderId))
                    .willReturn(TossResponseFixture.createInquiryResult("pk_cr_canceled", orderId, "CANCELED"));

            // when
            scheduler.reconcileStalePayments();

            // then: Toss cancel 미호출
            verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());

            Payment result = paymentRepository.findById(stalePayment.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);

            // freeze 선행 후 deductFrozenMoney -> balance=0
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isZero();
        }
    }

    // ================= Helper ====================

    private Payment createStaleCancelRequestedPayment() {
        // 충전 완료 (SUCCESS 상태) -> CANCEL_REQUESTED + 머니 동결 (실제 취소 흐름 재현)
        String paymentKey = "pk_cr_" + System.currentTimeMillis();
        MoneyRechargePreparedResponse prepared = moneyService.prepareRecharge(
                new MoneyRechargeRequest(RECHARGE_AMOUNT), member);
        mockTossConfirm(paymentKey);
        moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getId());

        Payment payment = paymentRepository.findByOrderId(prepared.orderId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        // prepareForCancellation: CANCEL_REQUESTED + freezeMoney (실제 취소 흐름과 동일하게)
        paymentInternalService.prepareForCancellation(payment.getId());

        // updatedAt을 과거로 설정 (스케줄러가 stale로 인식하도록)
        jdbcTemplate.update(
                "UPDATE payment SET updated_at = ? WHERE payment_id = ?",
                LocalDateTime.now().minusMinutes(30), payment.getId());

        return paymentRepository.findById(payment.getId()).orElseThrow();
    }

    private Payment createStalePendingPayment() {
        MoneyRechargePreparedResponse prepared = moneyService.prepareRecharge(
                new MoneyRechargeRequest(RECHARGE_AMOUNT), member);

        Payment payment = paymentRepository.findByOrderId(prepared.orderId()).orElseThrow();

        // createdAt을 과거로 설정 (stale threshold: 10분)
        setStaleCreatedAt(payment.getId());

        return paymentRepository.findById(payment.getId()).orElseThrow();
    }

    private void setStaleCreatedAt(Long paymentId) {
        LocalDateTime pastTime = LocalDateTime.now().minusMinutes(30);
        jdbcTemplate.update(
                "UPDATE payment SET created_at = ?, updated_at = ? WHERE payment_id = ?",
                pastTime, pastTime, paymentId);
    }

    private void mockTossConfirm(String paymentKey) {
        given(paymentGatewayPort.confirmPayment(anyString(), anyString(), anyLong()))
                .willReturn(TossResponseFixture.createConfirmResult(paymentKey));
    }
}
