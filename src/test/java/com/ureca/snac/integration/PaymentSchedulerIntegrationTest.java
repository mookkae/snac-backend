package com.ureca.snac.integration;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.fixture.TossResponseFixture;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.money.service.MoneyService;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.TossRetryableException;
import com.ureca.snac.payment.scheduler.PaymentReconciliationScheduler;
import com.ureca.snac.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;

import static com.ureca.snac.common.BaseCode.TOSS_API_CALL_ERROR;
import static com.ureca.snac.infra.TossErrorCode.TIMEOUT;
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
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PaymentGatewayAdapter paymentGatewayAdapter;

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
            scheduler.reconcileStalePendingPayments();

            // then
            verify(paymentGatewayAdapter, never()).inquirePaymentByOrderId(anyString());
        }

        @Test
        @DisplayName("성공 : Toss DONE -> 토스 취소 + 로컬 취소 + Payment CANCELED")
        void shouldCancelTossDonePayment() {
            // given
            Payment stalePayment = createStalePendingPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayAdapter.inquirePaymentByOrderId(orderId))
                    .willReturn(TossResponseFixture.createInquiryResponse("pk_done", orderId, "DONE"));

            // when
            scheduler.reconcileStalePendingPayments();

            // then
            verify(paymentGatewayAdapter).cancelPayment(eq("pk_done"), anyString());

            Payment result = paymentRepository.findById(stalePayment.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        @DisplayName("성공 : Toss CANCELED -> 로컬만 취소")
        void shouldCancelLocallyForCanceledToss() {
            // given
            Payment stalePayment = createStalePendingPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayAdapter.inquirePaymentByOrderId(orderId))
                    .willReturn(TossResponseFixture.createInquiryResponse("pk_canceled", orderId, "CANCELED"));

            // when
            scheduler.reconcileStalePendingPayments();

            // then: Toss cancel 미호출
            verify(paymentGatewayAdapter, never()).cancelPayment(anyString(), anyString());

            Payment result = paymentRepository.findById(stalePayment.getId()).orElseThrow();
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        @DisplayName("성공 : Toss 기록 없음(ExternalApiException) -> 로컬만 취소")
        void shouldCancelLocallyWhenTossNotFound() {
            // given
            Payment stalePayment = createStalePendingPayment();
            String orderId = stalePayment.getOrderId();

            given(paymentGatewayAdapter.inquirePaymentByOrderId(orderId))
                    .willThrow(new ExternalApiException(TOSS_API_CALL_ERROR, "NOT_FOUND_PAYMENT"));

            // when
            scheduler.reconcileStalePendingPayments();

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

            given(paymentGatewayAdapter.inquirePaymentByOrderId(orderId))
                    .willThrow(new TossRetryableException(TIMEOUT));

            // when
            scheduler.reconcileStalePendingPayments();

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

            given(paymentGatewayAdapter.inquirePaymentByOrderId(orderId))
                    .willReturn(TossResponseFixture.createInquiryResponse("pk_progress", orderId, "IN_PROGRESS"));

            // when
            scheduler.reconcileStalePendingPayments();

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

            given(paymentGatewayAdapter.inquirePaymentByOrderId(orderId))
                    .willReturn(TossResponseFixture.createInquiryResponse("pk_already", orderId, "DONE"));
            doThrow(new ExternalApiException(TOSS_API_CALL_ERROR, "ALREADY_CANCELED_PAYMENT"))
                    .when(paymentGatewayAdapter).cancelPayment(eq("pk_already"), anyString());

            // when
            scheduler.reconcileStalePendingPayments();

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
                    new MoneyRechargeRequest(RECHARGE_AMOUNT), member.getEmail());
            mockTossConfirm(paymentKey);
            moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());

            // Payment를 다시 PENDING으로 변경해서 스케줄러가 잡게 만들지 않음
            // 대신 createdAt을 과거로 -> 하지만 이미 SUCCESS라 Processor가 false 반환 확인
            Payment payment = paymentRepository.findByOrderId(prepared.orderId()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

            // stale로 만들어도 findStalePendingPayments는 PENDING 상태만 조회하므로 잡히지 않음
            // -> 결국 inquire 미호출
            scheduler.reconcileStalePendingPayments();

            verify(paymentGatewayAdapter, never()).inquirePaymentByOrderId(anyString());
        }
    }

    // ================= Helper ====================

    private Payment createStalePendingPayment() {
        MoneyRechargePreparedResponse prepared = moneyService.prepareRecharge(
                new MoneyRechargeRequest(RECHARGE_AMOUNT), member.getEmail());

        Payment payment = paymentRepository.findByOrderId(prepared.orderId()).orElseThrow();

        // createdAt을 과거로 설정 (stale threshold: 10분)
        setStaleCreatedAt(payment.getId());

        return paymentRepository.findById(payment.getId()).orElseThrow();
    }

    private void setStaleCreatedAt(Long paymentId) {
        LocalDateTime pastTime = LocalDateTime.now().minusMinutes(30);
        jdbcTemplate.update(
                "UPDATE payment SET created_at = ? WHERE payment_id = ?",
                pastTime, paymentId);
    }

    private void mockTossConfirm(String paymentKey) {
        given(paymentGatewayAdapter.confirmPayment(anyString(), anyString(), anyLong()))
                .willReturn(TossResponseFixture.createConfirmResponse(paymentKey));
    }
}
