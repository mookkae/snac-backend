package com.ureca.snac.payment.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import com.ureca.snac.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;
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

    @Mock
    private MemberRepository memberRepository;

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
                eventPublisher, memberRepository, meterRegistry
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
                "테스트 취소"
        );
    }

    @Nested
    @DisplayName("markAsCancelRequested 메서드")
    class MarkAsCancelRequestedTest {

        @Test
        @DisplayName("성공 : SUCCESS → CANCEL_REQUESTED 전환")
        void markAsCancelRequested_HappyPath() {
            // given
            Payment successPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(successPayment));

            // when
            paymentInternalService.markAsCancelRequested(1L);

            // then
            assertThat(successPayment.getStatus()).isEqualTo(PaymentStatus.CANCEL_REQUESTED);
        }
    }

    @Nested
    @DisplayName("completeCancellationForReconciliation 메서드")
    class CompleteCancellationForReconciliationTest {

        @Test
        @DisplayName("성공 : CANCEL_REQUESTED → CANCELED + Wallet 회수 + AssetHistory 기록")
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
            given(walletService.withdrawMoney(member.getId(), AMOUNT)).willReturn(5000L);

            // when
            paymentInternalService.completeCancellationForReconciliation(1L, "대사 취소");

            // then
            assertThat(cancelRequestedPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
            verify(walletService, times(1)).withdrawMoney(member.getId(), AMOUNT);
            verify(assetRecorder, times(1)).recordMoneyRechargeCancel(
                    member.getId(), 1L, AMOUNT, 5000L);
        }

        @Test
        @DisplayName("멱등성 : 이미 CANCELED → Wallet 미호출, 조기 반환")
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
            verify(walletService, never()).withdrawMoney(anyLong(), anyLong());
            verify(assetRecorder, never()).recordMoneyRechargeCancel(anyLong(), anyLong(), anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("compensateCancellationFailure 메서드")
    class CompensateCancellationFailureTest {

        @Test
        @DisplayName("보상 처리 실패 시 CompensationFailureEvent 발행")
        void compensateCancellationFailure_WhenFails_ShouldPublishEvent() {
            // given: Payment 조회 시 예외 발생하도록 설정
            Exception originalError = new RuntimeException("Original DB Error");
            given(paymentRepository.findById(anyLong()))
                    .willThrow(new RuntimeException("Compensation DB Error"));

            // when
            paymentInternalService.compensateCancellationFailure(
                    payment, member, cancelResponse, originalError);

            // then: CompensationFailureEvent 발행 확인
            ArgumentCaptor<CompensationFailureEvent> eventCaptor =
                    ArgumentCaptor.forClass(CompensationFailureEvent.class);
            verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

            CompensationFailureEvent capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent.paymentId()).isEqualTo(payment.getId());
            assertThat(capturedEvent.memberId()).isEqualTo(member.getId());
            assertThat(capturedEvent.amount()).isEqualTo(AMOUNT);
            assertThat(capturedEvent.orderId()).isEqualTo(ORDER_ID);
            assertThat(capturedEvent.paymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(capturedEvent.cancelReason()).isEqualTo(cancelResponse.reason());
            assertThat(capturedEvent.originalErrorMessage()).isEqualTo("Original DB Error");
            assertThat(capturedEvent.compensationErrorMessage()).isEqualTo("Compensation DB Error");

            // 메트릭 검증
            assertThat(meterRegistry.get("payment_compensation_triggered_total")
                    .counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("보상 처리 성공 시 CompensationFailureEvent 미발행")
        void compensateCancellationFailure_WhenSucceeds_ShouldNotPublishFailureEvent() {
            // given: 정상 처리되도록 설정
            Exception originalError = new RuntimeException("Original DB Error");
            given(paymentRepository.findById(anyLong())).willReturn(Optional.of(payment));
            given(paymentRepository.save(any(Payment.class))).willReturn(payment);

            // when
            paymentInternalService.compensateCancellationFailure(
                    payment, member, cancelResponse, originalError);

            // then: CompensationFailureEvent 미발행, PaymentCancelCompensationEvent만 발행
            ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

            Object capturedEvent = eventCaptor.getValue();
            assertThat(capturedEvent).isNotInstanceOf(CompensationFailureEvent.class);
        }
    }

    @Nested
    @DisplayName("processCancellationInDB 메서드")
    class ProcessCancellationInDBTest {

        @Test
        @DisplayName("성공 : Payment CANCELED + walletService.withdrawMoney + assetRecorder 호출")
        void processCancellationInDB_HappyPath() {
            // given
            Payment successPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(successPayment));
            given(walletService.withdrawMoney(member.getId(), AMOUNT)).willReturn(5000L);

            // when
            paymentInternalService.processCancellationInDB(successPayment, member, cancelResponse);

            // then
            assertThat(successPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
            verify(walletService, times(1)).withdrawMoney(member.getId(), AMOUNT);
            verify(assetRecorder, times(1)).recordMoneyRechargeCancel(
                    member.getId(), 1L, AMOUNT, 5000L);
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
            paymentInternalService.processCancellationInDB(canceledPayment, member, cancelResponse);

            // then
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
                    paymentInternalService.processCancellationInDB(payment, member, cancelResponse)
            ).isInstanceOf(com.ureca.snac.payment.exception.PaymentNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("processCompensation 메서드")
    class ProcessCompensationTest {

        @Test
        @DisplayName("멱등성 : 이미 보상 완료된 Payment -> withdrawMoney 호출 안 함")
        void processCompensation_AlreadyCompleted_SkipsWithdraw() {
            // given: compensationCompleted = true인 Payment
            Payment completedPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.CANCELED)
                    .paymentKey(PAYMENT_KEY)
                    .build();
            completedPayment.markCompensationCompleted();

            given(paymentRepository.findByIdForUpdate(1L))
                    .willReturn(Optional.of(completedPayment));

            PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                    1L, member.getId(), AMOUNT, "보상 처리", OffsetDateTime.now()
            );

            // when
            paymentInternalService.processCompensation(event);

            // then: withdrawMoney 호출 안 함
            verify(walletService, never()).withdrawMoney(anyLong(), anyLong());
            verify(assetRecorder, never()).recordMoneyRechargeCancel(anyLong(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("성공 : Payment 조회 + withdrawMoney + recordMoneyRechargeCancel + markCompensationCompleted")
        void processCompensation_HappyPath() {
            // given
            Payment canceledPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.CANCELED)
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(canceledPayment));
            given(memberRepository.findById(member.getId())).willReturn(Optional.of(member));
            given(walletService.withdrawMoney(member.getId(), AMOUNT)).willReturn(5000L);

            PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                    1L, member.getId(), AMOUNT, "보상 처리", OffsetDateTime.now()
            );

            // when
            paymentInternalService.processCompensation(event);

            // then
            verify(walletService, times(1)).withdrawMoney(member.getId(), AMOUNT);
            verify(assetRecorder, times(1)).recordMoneyRechargeCancel(
                    member.getId(), 1L, AMOUNT, 5000L);
            assertThat(canceledPayment.isCompensationCompleted()).isTrue();
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

        @Test
        @DisplayName("실패 : Member 없음 -> MemberNotFoundException")
        void processCompensation_MemberNotFound_ThrowsException() {
            // given
            Payment canceledPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.CANCELED)
                    .paymentKey(PAYMENT_KEY)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(canceledPayment));
            given(memberRepository.findById(member.getId())).willReturn(Optional.empty());

            PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                    1L, member.getId(), AMOUNT, "보상 처리", OffsetDateTime.now()
            );

            // when, then
            assertThatThrownBy(() -> paymentInternalService.processCompensation(event))
                    .isInstanceOf(com.ureca.snac.member.exception.MemberNotFoundException.class);
        }
    }
}
