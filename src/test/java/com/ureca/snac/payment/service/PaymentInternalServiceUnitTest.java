package com.ureca.snac.payment.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.common.metric.TransactionAwareMetricRecorder;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentMethod;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import com.ureca.snac.wallet.service.WalletService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * PaymentInternalService 단위 테스트
 * compensateCancellationFailure : 보상 처리 실패 시 CompensationFailureEvent 발행
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentInternalServiceUnitTest 단위 테스트")
class PaymentInternalServiceUnitTest {

    private PaymentInternalService paymentInternalService;
    private SimpleMeterRegistry meterRegistry;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private WalletService walletService;

    @Mock
    private AssetRecorder assetRecorder;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private Member member;
    private Payment payment;
    private PaymentCancelResponse cancelResponse;
    private static final Long AMOUNT = 10000L;
    private static final String PAYMENT_KEY = "test_payment_key";
    private static final String ORDER_ID = "snac_order_test_123";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        paymentInternalService = new PaymentInternalService(
                paymentRepository, walletService, assetRecorder,
                eventPublisher, new TransactionAwareMetricRecorder(meterRegistry),
                Clock.systemDefaultZone()
        );
        member = MemberFixture.createMember(1L);
        payment = PaymentFixture.builder()
                .id(1L)
                .member(member)
                .orderId(ORDER_ID)
                .amount(AMOUNT)
                .paymentKey(PAYMENT_KEY)
                .build();
        cancelResponse = new PaymentCancelResponse(
                PAYMENT_KEY,
                AMOUNT,
                OffsetDateTime.now(),
                "테스트 취소",
                false
        );
    }

    @Nested
    @DisplayName("prepareForCancellation 메서드")
    class PrepareForCancellationTest {

        @Test
        @DisplayName("성공 : SUCCESS -> CANCEL_REQUESTED 전환 + freezeMoney 호출")
        void prepareForCancellation_HappyPath() {
            // given
            Payment successPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.CARD)
                    .paidAt(OffsetDateTime.now())
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(successPayment));
            given(walletService.freezeMoney(member.getId(), AMOUNT)).willReturn(0L);

            // when
            paymentInternalService.prepareForCancellation(1L);

            // then
            assertThat(successPayment.getStatus()).isEqualTo(PaymentStatus.CANCEL_REQUESTED);
            verify(walletService, times(1)).freezeMoney(member.getId(), AMOUNT);
        }

        @Test
        @DisplayName("실패 : freezeMoney 실패 시 예외 전파 (@Transactional 롤백으로 Payment 상태 보존)")
        void prepareForCancellation_FreezeFails_PropagatesException() {
            // given
            Payment successPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.CARD)
                    .paidAt(OffsetDateTime.now())
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(successPayment));
            given(walletService.freezeMoney(member.getId(), AMOUNT))
                    .willThrow(InsufficientBalanceException.class);

            // when, then: 예외 전파 -> @Transactional 롤백으로 requestCancellation() 효과 취소됨
            assertThatThrownBy(() -> paymentInternalService.prepareForCancellation(1L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }
    }

    @Nested
    @DisplayName("completeCancellationForReconciliation 메서드")
    class CompleteCancellationForReconciliationTest {

        @Test
        @DisplayName("성공 : CANCEL_REQUESTED -> CANCELED + deductFrozenMoney + AssetHistory 기록")
        void completeCancellationForReconciliation_HappyPath() {
            // given
            Payment cancelRequestedPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.CANCEL_REQUESTED)
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(cancelRequestedPayment));
            given(walletService.deductFrozenMoney(member.getId(), AMOUNT)).willReturn(0L);

            // when
            paymentInternalService.completeCancellationForReconciliation(1L, "대사 취소");

            // then
            assertThat(cancelRequestedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
            verify(walletService, times(1)).deductFrozenMoney(member.getId(), AMOUNT);
            verify(walletService, never()).withdrawMoney(anyLong(), anyLong());
            verify(assetRecorder, times(1)).recordMoneyRechargeCancel(
                    member.getId(), 1L, AMOUNT, 0L);
        }

        @Test
        @DisplayName("멱등성 : 이미 CANCELED -> Wallet 미호출, 조기 반환")
        void completeCancellationForReconciliation_AlreadyCanceled_Skips() {
            // given
            Payment canceledPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.CANCELED)
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(canceledPayment));

            // when
            paymentInternalService.completeCancellationForReconciliation(1L, "대사 취소");

            // then
            verify(walletService, never()).deductFrozenMoney(anyLong(), anyLong());
            verify(walletService, never()).withdrawMoney(anyLong(), anyLong());
            verify(assetRecorder, never()).recordMoneyRechargeCancel(anyLong(), anyLong(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("compensateCancellationFailure 메서드")
    class CompensateCancellationFailureTest {

        @Test
        @DisplayName("성공 : Payment 상태 변경 없이 PaymentCancelCompensationEvent만 발행")
        void compensateCancellationFailure_ShouldOnlyPublishEvent_WithoutChangingPaymentStatus() {
            // given
            Exception originalError = new RuntimeException("Original DB Error");

            // when
            paymentInternalService.compensateCancellationFailure(
                    payment, member.getId(), cancelResponse, originalError);

            // then: PaymentCancelCompensationEvent만 발행 (Payment 상태 변경 없음)
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());
            assertThat(eventCaptor.getValue()).isInstanceOf(PaymentCancelCompensationEvent.class);
            assertThat(eventCaptor.getValue()).isNotInstanceOf(CompensationFailureEvent.class);

            // then: Payment 상태 변경 없음 — CANCEL_REQUESTED 유지로 대사 스케줄러 시야 내 보장
            verify(paymentRepository, never()).save(any(Payment.class));

            // 메트릭 검증
            assertThat(meterRegistry.get("payment_compensation_triggered_total")
                    .counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("실패 : Outbox 저장 실패 시 CompensationFailureEvent 발행")
        void compensateCancellationFailure_WhenOutboxFails_ShouldPublishFailureEvent() {
            // given: Outbox 저장(eventPublisher) 실패 시뮬레이션
            Exception originalError = new RuntimeException("Original DB Error");
            willThrow(new RuntimeException("Compensation DB Error"))
                    .given(eventPublisher).publishEvent(any(PaymentCancelCompensationEvent.class));

            // when
            paymentInternalService.compensateCancellationFailure(
                    payment, member.getId(), cancelResponse, originalError);

            // then: publishEvent 총 2회 (PaymentCancelCompensationEvent 1회 + CompensationFailureEvent 1회)
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture());

            CompensationFailureEvent capturedEvent = eventCaptor.getAllValues().stream()
                    .filter(e -> e instanceof CompensationFailureEvent)
                    .map(e -> (CompensationFailureEvent) e)
                    .findFirst().orElseThrow();
            assertThat(capturedEvent.paymentId()).isEqualTo(payment.getId());
            assertThat(capturedEvent.memberId()).isEqualTo(member.getId());
            assertThat(capturedEvent.amount()).isEqualTo(AMOUNT);
            assertThat(capturedEvent.orderId()).isEqualTo(ORDER_ID);
            assertThat(capturedEvent.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(capturedEvent.cancelReason()).isEqualTo(cancelResponse.reason());
            assertThat(capturedEvent.originalErrorMessage()).isEqualTo("Original DB Error");
            assertThat(capturedEvent.compensationErrorMessage()).isEqualTo("Compensation DB Error");
        }
    }

    @Nested
    @DisplayName("processCancellationInDB 메서드")
    class ProcessCancellationInDBTest {

        @Test
        @DisplayName("성공 : Payment CANCELED + deductFrozenMoney + assetRecorder 호출 (withdrawMoney 미호출)")
        void processCancellationInDB_HappyPath() {
            // given
            Payment cancelRequestedPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.CANCEL_REQUESTED)
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(cancelRequestedPayment));
            given(walletService.deductFrozenMoney(member.getId(), AMOUNT)).willReturn(0L);

            // when
            paymentInternalService.processCancellationInDB(cancelRequestedPayment.getId(), cancelResponse);

            // then
            assertThat(cancelRequestedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
            verify(walletService, times(1)).deductFrozenMoney(member.getId(), AMOUNT);
            verify(walletService, never()).withdrawMoney(anyLong(), anyLong());
            verify(assetRecorder, times(1)).recordMoneyRechargeCancel(
                    member.getId(), 1L, AMOUNT, 0L);
        }

        @Test
        @DisplayName("멱등성 : 이미 CANCELED -> walletService 미호출, 조기 종료")
        void processCancellationInDB_AlreadyCanceled_SkipsProcessing() {
            // given
            Payment canceledPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.CANCELED)
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(canceledPayment));

            // when
            paymentInternalService.processCancellationInDB(canceledPayment.getId(), cancelResponse);

            // then
            verify(walletService, never()).deductFrozenMoney(anyLong(), anyLong());
            verify(walletService, never()).withdrawMoney(anyLong(), anyLong());
            verify(assetRecorder, never()).recordMoneyRechargeCancel(anyLong(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("실패 : Payment 없음 -> PaymentNotFoundException")
        void processCancellationInDB_PaymentNotFound_ThrowsException() {
            // given
            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());

            // when, then
            assertThatThrownBy(() ->
                    paymentInternalService.processCancellationInDB(payment.getId(), cancelResponse)
            ).isInstanceOf(com.ureca.snac.payment.exception.PaymentNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("processCompensation 메서드")
    class ProcessCompensationTest {

        @Test
        @DisplayName("멱등성 : 이미 CANCELED -> deductFrozenMoney 미호출, 조기 반환")
        void processCompensation_AlreadyCanceled_SkipsDeductFrozen() {
            // given: 대사 스케줄러 또는 이전 보상으로 이미 CANCELED된 Payment
            // compensationCompleted 플래그 제거 — status == CANCELED 기반 멱등성
            Payment canceledPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.CANCELED)
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L))
                    .willReturn(Optional.of(canceledPayment));

            PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                    1L, member.getId(), AMOUNT, "보상 처리", OffsetDateTime.now()
            );

            // when
            paymentInternalService.processCompensation(event);

            // then: deductFrozenMoney 미호출
            verify(walletService, never()).deductFrozenMoney(anyLong(), anyLong());
            verify(walletService, never()).withdrawMoney(anyLong(), anyLong());
            verify(assetRecorder, never()).recordMoneyRechargeCancel(anyLong(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("성공 : CANCEL_REQUESTED -> CANCELED + deductFrozenMoney + recordMoneyRechargeCancel")
        void processCompensation_HappyPath() {
            // given: compensateCancellationFailure가 상태를 바꾸지 않으므로 CANCEL_REQUESTED
            Payment cancelRequestedPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.CANCEL_REQUESTED)
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(cancelRequestedPayment));
            given(walletService.deductFrozenMoney(member.getId(), AMOUNT)).willReturn(0L);

            PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                    1L, member.getId(), AMOUNT, "보상 처리", OffsetDateTime.now()
            );

            // when
            paymentInternalService.processCompensation(event);

            // then: CANCEL_REQUESTED -> CANCELED 전환
            assertThat(cancelRequestedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
            verify(walletService, times(1)).deductFrozenMoney(member.getId(), AMOUNT);
            verify(walletService, never()).withdrawMoney(anyLong(), anyLong());
            verify(assetRecorder, times(1)).recordMoneyRechargeCancel(
                    member.getId(), 1L, AMOUNT, 0L);
        }

        @Test
        @DisplayName("실패 : Payment 없음 -> PaymentNotFoundException")
        void processCompensation_PaymentNotFound_ThrowsException() {
            // given
            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.empty());

            PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                    1L, member.getId(), AMOUNT, "보상 처리", OffsetDateTime.now()
            );

            // when, then
            assertThatThrownBy(() -> paymentInternalService.processCompensation(event))
                    .isInstanceOf(com.ureca.snac.payment.exception.PaymentNotFoundException.class);
        }

    }

    @Nested
    @DisplayName("cancelPendingPayment 메서드")
    class CancelPendingPaymentTest {

        @Test
        @DisplayName("PENDING 상태 결제 -> 취소 성공, true 반환")
        void shouldCancelPendingPayment() {
            // given
            Payment pendingPayment = PaymentFixture.builder()
                    .id(1L).member(member).status(PaymentStatus.PENDING).build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(pendingPayment));

            // when
            boolean result = paymentInternalService.cancelPendingPayment(1L, "대사: 자동 취소");

            // then
            assertThat(result).isTrue();
            assertThat(pendingPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
            verify(walletService, never()).deductFrozenMoney(anyLong(), anyLong());
        }

        @Test
        @DisplayName("PENDING 아닌 상태 -> no-op, false 반환")
        void shouldReturnFalseForNonPendingPayment() {
            // given
            Payment successPayment = PaymentFixture.builder()
                    .id(2L).member(member).status(PaymentStatus.SUCCESS).build();

            given(paymentRepository.findByIdForUpdate(2L)).willReturn(Optional.of(successPayment));

            // when
            boolean result = paymentInternalService.cancelPendingPayment(2L, "대사: 자동 취소");

            // then
            assertThat(result).isFalse();
            assertThat(successPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @Test
        @DisplayName("존재하지 않는 Payment -> PaymentNotFoundException")
        void shouldThrowWhenPaymentNotFound() {
            given(paymentRepository.findByIdForUpdate(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> paymentInternalService.cancelPendingPayment(999L, "대사: 자동 취소"))
                    .isInstanceOf(com.ureca.snac.payment.exception.PaymentNotFoundException.class);
        }
    }
}
