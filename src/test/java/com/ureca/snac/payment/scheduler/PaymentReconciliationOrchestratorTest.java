package com.ureca.snac.payment.scheduler;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.AlreadyCanceledPaymentException;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.dto.GatewayPaymentStatus;
import com.ureca.snac.payment.port.out.dto.PaymentInquiryResult;
import com.ureca.snac.payment.port.out.exception.GatewayNotCancelableException;
import com.ureca.snac.payment.port.out.exception.GatewayTransientException;
import com.ureca.snac.payment.service.PaymentAlertNotifier;
import com.ureca.snac.payment.service.PaymentInternalService;
import com.ureca.snac.support.fixture.MemberFixture;

import java.time.OffsetDateTime;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_API_ERROR;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentReconciliationOrchestrator 단위 테스트")
class PaymentReconciliationOrchestratorTest {

    private PaymentReconciliationOrchestrator orchestrator;

    @Mock
    private PaymentGatewayPort paymentGatewayPort;

    @Mock
    private PaymentInternalService paymentInternalService;

    @Mock
    private PaymentAlertNotifier paymentAlertNotifier;

    private final Member member = MemberFixture.createMember(1L);

    @BeforeEach
    void setUp() {
        orchestrator = new PaymentReconciliationOrchestrator(
                paymentGatewayPort,
                paymentInternalService,
                paymentAlertNotifier
        );
    }

    // PENDING 경로
    @Nested
    @DisplayName("PENDING 대사 처리")
    class ReconcilePendingTest {

        @Test
        @DisplayName("토스 DONE -> 토스 취소 + 로컬 취소 + 알림")
        void shouldCancelDonePaymentOnToss() {
            Payment payment = createPending(1L, "order_1");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_1"))
                    .willReturn(tossResponse("pk_1", "order_1", "DONE"));
            given(paymentInternalService.cancelPendingPayment(eq(1L), anyString())).willReturn(true);

            orchestrator.reconcile(payment);

            verify(paymentGatewayPort).cancelPayment(eq("pk_1"), anyString());
            verify(paymentInternalService).cancelPendingPayment(eq(1L), anyString());
            verify(paymentAlertNotifier).alertReconciliationAutoCanceled(1L, "order_1", payment.getAmount(), "pk_1");
        }

        @Test
        @DisplayName("토스 DONE -> 로컬 취소 실패 -> critical 알림")
        void shouldAlertCriticalWhenLocalCancelFails() {
            Payment payment = createPending(2L, "order_2");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_2"))
                    .willReturn(tossResponse("pk_2", "order_2", "DONE"));
            given(paymentInternalService.cancelPendingPayment(eq(2L), anyString()))
                    .willThrow(new RuntimeException("DB connection lost"));

            orchestrator.reconcile(payment);

            verify(paymentAlertNotifier).alertReconciliationCancelFailed(
                    eq(2L), eq("order_2"), eq(payment.getAmount()), eq("pk_2"), eq("DB connection lost"));
        }

        @Test
        @DisplayName("토스 CANCELED -> 로컬만 취소")
        void shouldCancelLocallyForCanceledTossPayment() {
            Payment payment = createPending(3L, "order_3");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_3"))
                    .willReturn(tossResponseNoKey("order_3", "CANCELED"));

            orchestrator.reconcile(payment);

            verify(paymentInternalService).cancelPendingPayment(eq(3L), contains("CANCELED"));
            verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());
        }

        @Test
        @DisplayName("토스 조회 GatewayTransientException -> 스킵")
        void shouldSkipOnRetryableException() {
            Payment payment = createPending(4L, "order_4");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_4"))
                    .willThrow(new GatewayTransientException(new RuntimeException("timeout")));

            orchestrator.reconcile(payment);

            verifyNoInteractions(paymentInternalService, paymentAlertNotifier);
        }

        @Test
        @DisplayName("토스 조회 PaymentNotFoundException(기록 없음) -> 로컬만 취소")
        void shouldCancelLocallyWhenTossNotFound() {
            Payment payment = createPending(5L, "order_5");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_5"))
                    .willThrow(new PaymentNotFoundException());

            orchestrator.reconcile(payment);

            verify(paymentInternalService).cancelPendingPayment(eq(5L), contains("토스 결제 기록 없음"));
            verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());
        }

        @Test
        @DisplayName("토스 DONE -> Toss 취소 AlreadyCanceledException -> 로컬 취소 진행")
        void shouldCancelLocallyWhenTossAlreadyCanceled() {
            Payment payment = createPending(6L, "order_6");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_6"))
                    .willReturn(tossResponse("pk_6", "order_6", "DONE"));
            doThrow(new AlreadyCanceledPaymentException())
                    .when(paymentGatewayPort).cancelPayment(eq("pk_6"), anyString());
            given(paymentInternalService.cancelPendingPayment(eq(6L), anyString())).willReturn(true);

            orchestrator.reconcile(payment);

            verify(paymentInternalService).cancelPendingPayment(eq(6L), anyString());
            verify(paymentAlertNotifier).alertReconciliationAutoCanceled(6L, "order_6", payment.getAmount(), "pk_6");
        }

        @Test
        @DisplayName("토스 DONE -> Toss 취소 GatewayTransientException -> 스킵")
        void shouldSkipWhenTossCancelRetryable() {
            Payment payment = createPending(7L, "order_7");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_7"))
                    .willReturn(tossResponse("pk_7", "order_7", "DONE"));
            doThrow(new GatewayTransientException(new RuntimeException("timeout")))
                    .when(paymentGatewayPort).cancelPayment(eq("pk_7"), anyString());

            orchestrator.reconcile(payment);

            verifyNoInteractions(paymentInternalService, paymentAlertNotifier);
        }

        @Test
        @DisplayName("토스 DONE -> Toss 취소 GatewayNotCancelableException -> 수동 복구 알림, 로컬 취소 없음")
        void shouldAlertAndSkipLocalCancelWhenTossNotCancelable() {
            Payment payment = createPending(8L, "order_8");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_8"))
                    .willReturn(tossResponse("pk_8", "order_8", "DONE"));
            doThrow(new GatewayNotCancelableException())
                    .when(paymentGatewayPort).cancelPayment(eq("pk_8"), anyString());

            orchestrator.reconcile(payment);

            verify(paymentAlertNotifier).alertCancellationRejectedByGateway(
                    eq(8L), eq("order_8"), eq(payment.getAmount()), eq("pk_8"), eq("대사-PENDING"));
            verifyNoInteractions(paymentInternalService);
        }

        @Test
        @DisplayName("토스 DONE -> Toss 취소 ExternalApiException -> Fail-Safe 알림")
        void shouldAlertAndRetryNextCycleWhenTossCancelUnclassifiable() {
            Payment payment = createPending(9L, "order_9");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_9"))
                    .willReturn(tossResponse("pk_9", "order_9", "DONE"));
            doThrow(new ExternalApiException(PAYMENT_GATEWAY_API_ERROR, "UNKNOWN_ERROR"))
                    .when(paymentGatewayPort).cancelPayment(eq("pk_9"), anyString());

            orchestrator.reconcile(payment);

            verify(paymentAlertNotifier).alertReconciliationCancelFailed(
                    eq(9L), eq("order_9"), eq(payment.getAmount()), eq("pk_9"), anyString());
            verifyNoInteractions(paymentInternalService);
        }

        @Test
        @DisplayName("토스 진행 중 상태(WAITING_FOR_DEPOSIT) -> 스킵")
        void shouldSkipInProgressTossPayment() {
            Payment payment = createPending(10L, "order_10");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_10"))
                    .willReturn(tossResponseNoKey("order_10", "WAITING_FOR_DEPOSIT"));

            orchestrator.reconcile(payment);

            verifyNoInteractions(paymentInternalService, paymentAlertNotifier);
        }
    }

    // ─────────────────────────────────────────────
    // CANCEL_REQUESTED 경로
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("CANCEL_REQUESTED 대사 처리")
    class ReconcileCancelRequestedTest {

        @Test
        @DisplayName("Toss DONE -> Toss 취소 + completeCancellationForReconciliation + 알림")
        void shouldCompleteCancellationWithTossDone() {
            Payment payment = createCancelRequested(11L, "order_11", "pk_11");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_11"))
                    .willReturn(tossResponse("pk_11", "order_11", "DONE"));

            orchestrator.reconcile(payment);

            verify(paymentGatewayPort).cancelPayment(eq("pk_11"), anyString());
            verify(paymentInternalService).completeCancellationForReconciliation(eq(11L), anyString());
            verify(paymentAlertNotifier).alertReconciliationAutoCanceled(11L, "order_11", payment.getAmount(), "pk_11");
            verify(paymentInternalService, never()).cancelPendingPayment(anyLong(), anyString());
        }

        @Test
        @DisplayName("Toss CANCELED -> Toss 취소 스킵 + completeCancellationForReconciliation + 알림")
        void shouldCompleteCancellationWithTossCanceled() {
            Payment payment = createCancelRequested(12L, "order_12", "pk_12");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_12"))
                    .willReturn(tossResponseNoKey("order_12", "CANCELED"));

            orchestrator.reconcile(payment);

            verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());
            verify(paymentInternalService).completeCancellationForReconciliation(eq(12L), anyString());
            verify(paymentAlertNotifier).alertReconciliationAutoCanceled(12L, "order_12", payment.getAmount(), "pk_12");
        }

        @Test
        @DisplayName("Toss 조회 GatewayTransientException -> 스킵")
        void shouldSkipOnRetryableInquiry() {
            Payment payment = createCancelRequested(13L, "order_13", "pk_13");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_13"))
                    .willThrow(new GatewayTransientException(new RuntimeException("timeout")));

            orchestrator.reconcile(payment);

            verifyNoInteractions(paymentInternalService, paymentAlertNotifier);
        }

        @Test
        @DisplayName("Toss 조회 ExternalApiException -> Fail-Safe: frozen 유지 + 알림")
        void shouldKeepFrozenAndAlertWhenTossInquiryFails() {
            Payment payment = createCancelRequested(14L, "order_14", "pk_14");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_14"))
                    .willThrow(new ExternalApiException(PAYMENT_GATEWAY_API_ERROR, "NOT_FOUND_PAYMENT"));

            orchestrator.reconcile(payment);

            verify(paymentInternalService, never()).completeCancellationForReconciliation(anyLong(), anyString());
            verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());
            verify(paymentAlertNotifier).alertReconciliationCancelFailed(
                    eq(14L), anyString(), anyLong(), eq("pk_14"), anyString());
        }

        @Test
        @DisplayName("Toss DONE + cancelPayment GatewayTransientException -> 스킵")
        void shouldSkipWhenTossCancelRetryable() {
            Payment payment = createCancelRequested(15L, "order_15", "pk_15");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_15"))
                    .willReturn(tossResponse("pk_15", "order_15", "DONE"));
            doThrow(new GatewayTransientException(new RuntimeException("timeout")))
                    .when(paymentGatewayPort).cancelPayment(eq("pk_15"), anyString());

            orchestrator.reconcile(payment);

            verifyNoInteractions(paymentInternalService, paymentAlertNotifier);
        }

        @Test
        @DisplayName("Toss DONE + cancelPayment AlreadyCanceledException -> 로컬 취소 완료 처리")
        void shouldCompleteCancellationWhenTossAlreadyCanceled() {
            Payment payment = createCancelRequested(16L, "order_16", "pk_16");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_16"))
                    .willReturn(tossResponse("pk_16", "order_16", "DONE"));
            doThrow(new AlreadyCanceledPaymentException())
                    .when(paymentGatewayPort).cancelPayment(eq("pk_16"), anyString());

            orchestrator.reconcile(payment);

            verify(paymentInternalService).completeCancellationForReconciliation(eq(16L), anyString());
            verify(paymentAlertNotifier).alertReconciliationAutoCanceled(16L, "order_16", payment.getAmount(), "pk_16");
        }

        @Test
        @DisplayName("Toss DONE + cancelPayment GatewayNotCancelableException -> frozen 해제 + SUCCESS 복구 + 알림")
        void shouldRevertCancellationWhenTossNotCancelable() {
            Payment payment = createCancelRequested(17L, "order_17", "pk_17");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_17"))
                    .willReturn(tossResponse("pk_17", "order_17", "DONE"));
            doThrow(new GatewayNotCancelableException())
                    .when(paymentGatewayPort).cancelPayment(eq("pk_17"), anyString());

            orchestrator.reconcile(payment);

            verify(paymentInternalService).handleCancellationRejected(eq(17L));
            verify(paymentAlertNotifier).alertCancellationRejectedByGateway(
                    eq(17L), eq("order_17"), eq(payment.getAmount()), eq("pk_17"), eq("스케줄러"));
            verify(paymentInternalService, never()).completeCancellationForReconciliation(anyLong(), anyString());
        }

        @Test
        @DisplayName("Toss DONE + cancelPayment ExternalApiException -> Fail-Safe: frozen 유지 + 알림")
        void shouldKeepFrozenAndAlertWhenTossCancelUnclassifiable() {
            Payment payment = createCancelRequested(18L, "order_18", "pk_18");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_18"))
                    .willReturn(tossResponse("pk_18", "order_18", "DONE"));
            doThrow(new ExternalApiException(PAYMENT_GATEWAY_API_ERROR, "UNKNOWN_ERROR"))
                    .when(paymentGatewayPort).cancelPayment(eq("pk_18"), anyString());

            orchestrator.reconcile(payment);

            verify(paymentInternalService, never()).completeCancellationForReconciliation(anyLong(), anyString());
            verify(paymentInternalService, never()).handleCancellationRejected(anyLong());
            verify(paymentAlertNotifier).alertReconciliationCancelFailed(
                    eq(18L), eq("order_18"), eq(payment.getAmount()), eq("pk_18"), anyString());
        }

        @Test
        @DisplayName("Toss 비종료 상태(READY) -> 스킵")
        void shouldSkipWhenTossInProgress() {
            Payment payment = createCancelRequested(19L, "order_19", "pk_19");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_19"))
                    .willReturn(tossResponseNoKey("order_19", "READY"));

            orchestrator.reconcile(payment);

            verifyNoInteractions(paymentInternalService, paymentAlertNotifier);
        }

        @Test
        @DisplayName("completeCancellation 실패 -> alertReconciliationCancelFailed")
        void shouldAlertCancelFailedWhenCompleteCancellationThrows() {
            Payment payment = createCancelRequested(20L, "order_20", "pk_20");
            given(paymentGatewayPort.inquirePaymentByOrderId("order_20"))
                    .willReturn(tossResponseNoKey("order_20", "CANCELED"));
            doThrow(new RuntimeException("DB error"))
                    .when(paymentInternalService).completeCancellationForReconciliation(eq(20L), anyString());

            orchestrator.reconcile(payment);

            verify(paymentAlertNotifier).alertReconciliationCancelFailed(
                    eq(20L), eq("order_20"), eq(payment.getAmount()), eq("pk_20"), eq("DB error"));
        }
    }

    // ─────────────────────────────────────────────
    // 헬퍼
    // ─────────────────────────────────────────────

    private Payment createPending(Long id, String orderId) {
        return PaymentFixture.builder()
                .id(id).member(member).orderId(orderId).status(PaymentStatus.PENDING).build();
    }

    private Payment createCancelRequested(Long id, String orderId, String paymentKey) {
        return PaymentFixture.builder()
                .id(id).member(member).orderId(orderId).paymentKey(paymentKey)
                .status(PaymentStatus.CANCEL_REQUESTED).build();
    }

    private PaymentInquiryResult tossResponse(String paymentKey, String orderId, String status) {
        return new PaymentInquiryResult(toGatewayStatus(status), paymentKey, orderId, 10000L, "카드", OffsetDateTime.now());
    }

    private PaymentInquiryResult tossResponseNoKey(String orderId, String status) {
        return new PaymentInquiryResult(toGatewayStatus(status), null, orderId, 10000L, "카드", null);
    }

    private GatewayPaymentStatus toGatewayStatus(String tossStatus) {
        return switch (tossStatus) {
            case "DONE" -> GatewayPaymentStatus.DONE;
            case "CANCELED", "ABORTED", "EXPIRED" -> GatewayPaymentStatus.CANCELED;
            case "READY" -> GatewayPaymentStatus.READY;
            case "IN_PROGRESS", "WAITING_FOR_DEPOSIT" -> GatewayPaymentStatus.IN_PROGRESS;
            default -> GatewayPaymentStatus.UNKNOWN;
        };
    }
}
