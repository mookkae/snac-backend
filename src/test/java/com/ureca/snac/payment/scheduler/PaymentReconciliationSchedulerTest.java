package com.ureca.snac.payment.scheduler;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.TossErrorCode;
import com.ureca.snac.infra.dto.response.TossPaymentInquiryResponse;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.TossRetryableException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.payment.service.PaymentAlertService;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.OffsetDateTime;
import java.util.List;

import static com.ureca.snac.common.BaseCode.TOSS_API_CALL_ERROR;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentReconciliationScheduler 단위 테스트")
class PaymentReconciliationSchedulerTest {

    private PaymentReconciliationScheduler scheduler;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGatewayAdapter paymentGatewayAdapter;

    @Mock
    private PaymentReconciliationProcessor processor;

    @Mock
    private PaymentAlertService paymentAlertService;

    private final Member member = MemberFixture.createMember(1L);

    @BeforeEach
    void setUp() {
        scheduler = new PaymentReconciliationScheduler(
                paymentRepository,
                paymentGatewayAdapter,
                processor,
                paymentAlertService,
                10,  // staleThresholdMinutes
                50   // batchSize
        );
    }

    @Nested
    @DisplayName("reconcileStalePendingPayments 메서드")
    class ReconcileStalePendingPaymentsTest {

        @Test
        @DisplayName("미결 결제 없음 -> 조기 종료")
        void shouldEarlyExitWhenNoStalePayments() {
            // given
            given(paymentRepository.findStalePendingPayments(
                    eq(PaymentStatus.PENDING), any(), eq(PageRequest.of(0, 50))))
                    .willReturn(List.of());

            // when
            scheduler.reconcileStalePendingPayments();

            // then
            verifyNoInteractions(paymentGatewayAdapter, processor, paymentAlertService);
        }

        @Test
        @DisplayName("토스 DONE 상태 -> 토스 취소 + 로컬 취소 + 알림")
        void shouldCancelDonePaymentOnToss() {
            // given
            Payment payment = createStalePendingPayment(1L, "order_1");

            given(paymentRepository.findStalePendingPayments(
                    eq(PaymentStatus.PENDING), any(), any()))
                    .willReturn(List.of(payment));

            TossPaymentInquiryResponse tossResponse = new TossPaymentInquiryResponse(
                    "pk_done_1", "order_1", "DONE", "카드", 10000L, OffsetDateTime.now());

            given(paymentGatewayAdapter.inquirePaymentByOrderId("order_1"))
                    .willReturn(tossResponse);
            given(processor.cancelPayment(eq(1L), anyString())).willReturn(true);

            // when
            scheduler.reconcileStalePendingPayments();

            // then
            verify(paymentGatewayAdapter).cancelPayment(eq("pk_done_1"), anyString());
            verify(processor).cancelPayment(eq(1L), anyString());
            verify(paymentAlertService).alertReconciliationAutoCanceled(payment, "pk_done_1");
        }

        @Test
        @DisplayName("토스 DONE -> 토스 취소 성공 -> 로컬 취소 실패 -> critical 알림")
        void shouldAlertCriticalWhenLocalCancelFails() {
            // given
            Payment payment = createStalePendingPayment(2L, "order_2");

            given(paymentRepository.findStalePendingPayments(
                    eq(PaymentStatus.PENDING), any(), any()))
                    .willReturn(List.of(payment));

            TossPaymentInquiryResponse tossResponse = new TossPaymentInquiryResponse(
                    "pk_done_2", "order_2", "DONE", "카드", 10000L, OffsetDateTime.now());

            given(paymentGatewayAdapter.inquirePaymentByOrderId("order_2"))
                    .willReturn(tossResponse);
            given(processor.cancelPayment(eq(2L), anyString()))
                    .willThrow(new RuntimeException("DB connection lost"));

            // when
            scheduler.reconcileStalePendingPayments();

            // then
            verify(paymentGatewayAdapter).cancelPayment(eq("pk_done_2"), anyString());
            verify(paymentAlertService).alertReconciliationCancelFailed(
                    eq(payment), eq("pk_done_2"), eq("DB connection lost"));
        }

        @Test
        @DisplayName("토스 CANCELED/ABORTED 상태 -> 로컬만 취소")
        void shouldCancelLocallyForCanceledTossPayment() {
            // given
            Payment payment = createStalePendingPayment(3L, "order_3");

            given(paymentRepository.findStalePendingPayments(
                    eq(PaymentStatus.PENDING), any(), any()))
                    .willReturn(List.of(payment));

            TossPaymentInquiryResponse tossResponse = new TossPaymentInquiryResponse(
                    "pk_3", "order_3", "CANCELED", "카드", 10000L, null);

            given(paymentGatewayAdapter.inquirePaymentByOrderId("order_3"))
                    .willReturn(tossResponse);

            // when
            scheduler.reconcileStalePendingPayments();

            // then
            verify(processor).cancelPayment(eq(3L), contains("CANCELED"));
            verify(paymentGatewayAdapter, never()).cancelPayment(anyString(), anyString());
        }

        @Test
        @DisplayName("토스 조회 일시적 오류(TossRetryableException) -> 스킵")
        void shouldSkipOnRetryableException() {
            // given
            Payment payment = createStalePendingPayment(4L, "order_4");

            given(paymentRepository.findStalePendingPayments(
                    eq(PaymentStatus.PENDING), any(), any()))
                    .willReturn(List.of(payment));

            given(paymentGatewayAdapter.inquirePaymentByOrderId("order_4"))
                    .willThrow(new TossRetryableException(TossErrorCode.TIMEOUT));

            // when
            scheduler.reconcileStalePendingPayments();

            // then
            verifyNoInteractions(processor, paymentAlertService);
        }

        @Test
        @DisplayName("토스 조회 NOT_FOUND -> 로컬만 취소")
        void shouldCancelLocallyWhenTossNotFound() {
            // given
            Payment payment = createStalePendingPayment(5L, "order_5");

            given(paymentRepository.findStalePendingPayments(
                    eq(PaymentStatus.PENDING), any(), any()))
                    .willReturn(List.of(payment));

            given(paymentGatewayAdapter.inquirePaymentByOrderId("order_5"))
                    .willThrow(new ExternalApiException(TOSS_API_CALL_ERROR, "NOT_FOUND_PAYMENT"));

            // when
            scheduler.reconcileStalePendingPayments();

            // then
            verify(processor).cancelPayment(eq(5L), contains("토스 결제 기록 없음"));
            verify(paymentGatewayAdapter, never()).cancelPayment(anyString(), anyString());
        }

        @Test
        @DisplayName("토스 DONE -> 토스 취소 시 ALREADY_CANCELED 예외 -> 로컬 취소 진행")
        void shouldCancelLocallyWhenTossAlreadyCanceled() {
            // given
            Payment payment = createStalePendingPayment(6L, "order_6");

            given(paymentRepository.findStalePendingPayments(
                    eq(PaymentStatus.PENDING), any(), any()))
                    .willReturn(List.of(payment));

            TossPaymentInquiryResponse tossResponse = new TossPaymentInquiryResponse(
                    "pk_done_6", "order_6", "DONE", "카드", 10000L, OffsetDateTime.now());

            given(paymentGatewayAdapter.inquirePaymentByOrderId("order_6"))
                    .willReturn(tossResponse);
            doThrow(new ExternalApiException(TOSS_API_CALL_ERROR, "ALREADY_CANCELED_PAYMENT"))
                    .when(paymentGatewayAdapter).cancelPayment(eq("pk_done_6"), anyString());
            given(processor.cancelPayment(eq(6L), anyString())).willReturn(true);

            // when
            scheduler.reconcileStalePendingPayments();

            // then
            verify(processor).cancelPayment(eq(6L), anyString());
            verify(paymentAlertService).alertReconciliationAutoCanceled(payment, "pk_done_6");
        }

        @Test
        @DisplayName("토스 DONE -> 토스 취소 TossRetryableException -> PENDING 유지 (스킵)")
        void shouldSkipWhenTossCancelRetryable() {
            // given
            Payment payment = createStalePendingPayment(8L, "order_8");

            given(paymentRepository.findStalePendingPayments(
                    eq(PaymentStatus.PENDING), any(), any()))
                    .willReturn(List.of(payment));

            TossPaymentInquiryResponse tossResponse = new TossPaymentInquiryResponse(
                    "pk_done_8", "order_8", "DONE", "카드", 10000L, OffsetDateTime.now());

            given(paymentGatewayAdapter.inquirePaymentByOrderId("order_8"))
                    .willReturn(tossResponse);
            doThrow(new TossRetryableException(TossErrorCode.TIMEOUT))
                    .when(paymentGatewayAdapter).cancelPayment(eq("pk_done_8"), anyString());

            // when
            scheduler.reconcileStalePendingPayments();

            // then
            verifyNoInteractions(processor, paymentAlertService);
        }

        @Test
        @DisplayName("토스 진행 중 상태(WAITING_FOR_DEPOSIT) -> 스킵")
        void shouldSkipInProgressTossPayment() {
            // given
            Payment payment = createStalePendingPayment(7L, "order_7");

            given(paymentRepository.findStalePendingPayments(
                    eq(PaymentStatus.PENDING), any(), any()))
                    .willReturn(List.of(payment));

            TossPaymentInquiryResponse tossResponse = new TossPaymentInquiryResponse(
                    "pk_7", "order_7", "WAITING_FOR_DEPOSIT", "가상계좌", 10000L, null);

            given(paymentGatewayAdapter.inquirePaymentByOrderId("order_7"))
                    .willReturn(tossResponse);

            // when
            scheduler.reconcileStalePendingPayments();

            // then
            verifyNoInteractions(processor, paymentAlertService);
        }
    }

    private Payment createStalePendingPayment(Long id, String orderId) {
        return PaymentFixture.builder()
                .id(id)
                .member(member)
                .orderId(orderId)
                .status(PaymentStatus.PENDING)
                .build();
    }
}
