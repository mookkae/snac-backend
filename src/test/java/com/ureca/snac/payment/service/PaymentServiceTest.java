package com.ureca.snac.payment.service;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.AlreadyUsedRechargeCannotCancelException;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.exception.GatewayTransientException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentCancelResponseFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * PaymentService 단위 테스트
 * preparePayment: 결제 준비 (Payment 생성)
 * cancelPayment: 결제 취소 (토스 API + DB 처리 + 보상 로직)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentServiceTest 단위 테스트")
class PaymentServiceTest {

    private PaymentServiceImpl paymentService;
    private SimpleMeterRegistry meterRegistry;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGatewayPort paymentGatewayPort;

    @Mock
    private PaymentInternalService paymentInternalService;

    @Mock
    private PaymentAlertNotifier paymentAlertNotifier;

    private Member member;
    private Payment successPayment;
    private PaymentCancelResponse cancelResponse;
    private com.ureca.snac.payment.port.out.dto.PaymentCancelResult cancelResult;

    private static final String PAYMENT_KEY = "test_payment_key";
    private static final String ORDER_ID = "snac_order_test_123";
    private static final Long AMOUNT = 10000L;
    private static final String CANCEL_REASON = "고객 요청";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        paymentService = new PaymentServiceImpl(
                paymentRepository, paymentGatewayPort,
                paymentInternalService, paymentAlertNotifier, meterRegistry,
                Clock.systemDefaultZone()
        );
        member = MemberFixture.createMember(1L);
        successPayment = PaymentFixture.createSuccessPayment(member);
        cancelResponse = PaymentCancelResponseFixture.create(PAYMENT_KEY, AMOUNT, CANCEL_REASON);
        cancelResult = new com.ureca.snac.payment.port.out.dto.PaymentCancelResult(PAYMENT_KEY, AMOUNT, java.time.OffsetDateTime.now(), CANCEL_REASON);
    }

    @Nested
    @DisplayName("preparePayment 메서드")
    class PreparePaymentTest {

        @Test
        @DisplayName("정상 : Payment 생성 후 저장")
        void preparePayment_HappyPath() {
            // given
            Payment expectedPayment = PaymentFixture.builder()
                    .member(member)
                    .amount(AMOUNT)
                    .build();
            given(paymentRepository.save(any(Payment.class))).willReturn(expectedPayment);

            // when
            Payment result = paymentService.preparePayment(member, AMOUNT);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getAmount()).isEqualTo(AMOUNT);
            verify(paymentRepository, times(1)).save(any(Payment.class));
        }
    }

    @Nested
    @DisplayName("cancelPayment 메서드")
    class CancelPaymentTest {

        @Test
        @DisplayName("정상 : 토스 취소 -> DB 처리 모두 성공")
        void cancelPayment_HappyPath() {
            // given
            given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                    .willReturn(Optional.of(successPayment));
            given(paymentGatewayPort.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                    .willReturn(cancelResult);

            // when
            paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, member.getId());

            // then
            verify(paymentInternalService, times(1))
                    .prepareForCancellation(successPayment.getId());
            verify(paymentGatewayPort, times(1))
                    .cancelPayment(PAYMENT_KEY, CANCEL_REASON);
            verify(paymentInternalService, times(1))
                    .processCancellationInDB(any(Long.class), any(PaymentCancelResponse.class));

            // 메트릭 검증
            assertThat(meterRegistry.get("payment_cancel_total")
                    .tag("status", "success").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("보상 처리 : 토스 취소 성공 + DB 실패 시 보상 처리 호출")
        void cancelPayment_DBFailure_CompensationTriggered() {
            // given
            given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                    .willReturn(Optional.of(successPayment));
            given(paymentGatewayPort.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                    .willReturn(cancelResult);

            RuntimeException dbException = new RuntimeException("DB Connection Failed");
            doThrow(dbException).when(paymentInternalService)
                    .processCancellationInDB(any(Long.class), any(PaymentCancelResponse.class));

            // when, then
            assertThatThrownBy(() ->
                    paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, member.getId())
            ).isInstanceOf(RuntimeException.class)
                    .hasMessage("DB Connection Failed");

            // 보상 처리 호출 확인 (PaymentInternalService로 위임)
            verify(paymentInternalService, times(1))
                    .compensateCancellationFailure(
                            any(Payment.class),
                            any(Long.class),
                            any(PaymentCancelResponse.class),
                            any(Exception.class)
                    );

            // 메트릭 검증
            assertThat(meterRegistry.get("payment_cancel_total")
                    .tag("status", "compensation_triggered").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Fail-Safe : GatewayTransientException -> frozen 유지, 취소 처리 중 응답 반환 (processCancellationInDB 미호출)")
        void cancelPayment_TossRetryableException_KeepsFrozenAndReturnsProcessing() {
            // given
            given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                    .willReturn(Optional.of(successPayment));
            given(paymentGatewayPort.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                    .willThrow(new GatewayTransientException(new RuntimeException("timeout")));

            // when
            PaymentCancelResponse result = paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, member.getId());

            // then: 취소 처리 중 응답 반환
            assertThat(result.processing()).isTrue();
            assertThat(result.paymentKey()).isEqualTo(PAYMENT_KEY);

            // frozen 유지: prepareForCancellation 호출됨, processCancellationInDB 호출 안 됨
            verify(paymentInternalService, times(1)).prepareForCancellation(successPayment.getId());
            verify(paymentInternalService, never())
                    .processCancellationInDB(any(), any());

            // 메트릭 검증
            assertThat(meterRegistry.get("payment_cancel_total")
                    .tag("status", "processing").counter().count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Fail-Safe : 분류되지 않은 예외 -> frozen 유지, processing 응답 반환 (예외 전파 안 함)")
        void cancelPayment_UnclassifiedException_KeepsFrozenAndReturnsProcessing() {
            // given
            given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                    .willReturn(Optional.of(successPayment));
            given(paymentGatewayPort.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                    .willThrow(new ExternalApiException(
                            com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_API_ERROR, "네트워크 오류"));

            // when: Fail-Safe — 예외 전파 없이 processing 응답 반환
            PaymentCancelResponse response = paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, member.getId());

            // then: processing 상태 응답
            assertThat(response.processing()).isTrue();

            // frozen 유지: prepareForCancellation 호출됨, processCancellationInDB 호출 안 됨
            verify(paymentInternalService, times(1)).prepareForCancellation(successPayment.getId());
            verify(paymentInternalService, never())
                    .processCancellationInDB(any(), any());
        }

        @Test
        @DisplayName("예외 : 잔액 부족 시 취소 불가 (AlreadyUsedRechargeCannotCancelException)")
        void cancelPayment_InsufficientBalance_ThrowsException() {
            // given : prepareForCancellation이 InsufficientBalanceException 발생 -> AlreadyUsedRechargeCannotCancelException으로 변환
            given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                    .willReturn(Optional.of(successPayment));
            doThrow(new InsufficientBalanceException())
                    .when(paymentInternalService).prepareForCancellation(successPayment.getId());

            // when, then
            assertThatThrownBy(() ->
                    paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, member.getId())
            ).isInstanceOf(AlreadyUsedRechargeCannotCancelException.class);

            // 외부 API 호출 안 함
            verify(paymentGatewayPort, never())
                    .cancelPayment(anyString(), anyString());
        }

        @Test
        @DisplayName("실패 : 결제 없음 -> PaymentNotFoundException")
        void cancelPayment_PaymentNotFound_ThrowsException() {
            // given
            given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                    .willReturn(Optional.empty());

            // when, then
            assertThatThrownBy(() ->
                    paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, member.getId())
            ).isInstanceOf(PaymentNotFoundException.class);

            verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("markAsCanceled 메서드")
    class MarkAsCanceledTest {

        @Test
        @DisplayName("성공 : PENDING 결제 -> CANCELED 전환")
        void markAsCanceled_PendingPayment_TransitionsToCanceled() {
            // given
            Payment pendingPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .build();
            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(pendingPayment));

            // when
            paymentService.markAsCanceled(1L, "Auto-cancel");

            // then
            assertThat(pendingPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(pendingPayment.getCancelReason()).isEqualTo("Auto-cancel");
        }

        @Test
        @DisplayName("멱등성 : 이미 CANCELED 결제 -> no-op (예외 없음, 상태 유지)")
        void markAsCanceled_AlreadyCanceled_NoOp() {
            // given: 이미 CANCELED 상태인 Payment (대사 스케줄러가 먼저 처리한 경우 등)
            Payment canceledPayment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .status(PaymentStatus.CANCELED)
                    .build();
            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(canceledPayment));

            // when & then: 예외 없이 정상 종료, 상태 변경 없음
            paymentService.markAsCanceled(1L, "재시도");
            assertThat(canceledPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        @DisplayName("실패 : 결제 없음 -> PaymentNotFoundException")
        void markAsCanceled_PaymentNotFound_ThrowsException() {
            // given
            given(paymentRepository.findByIdForUpdate(999L)).willReturn(Optional.empty());

            // when, then
            assertThatThrownBy(() -> paymentService.markAsCanceled(999L, "test"))
                    .isInstanceOf(PaymentNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("findAndValidateForConfirmation 메서드")
    class FindAndValidateForConfirmationTest {

        @Test
        @DisplayName("성공 : 유효한 orderId + amount + member -> Payment 반환")
        void findAndValidate_ValidConditions_ReturnsPayment() {
            // given
            Payment pendingPayment = PaymentFixture.builder()
                    .member(member)
                    .orderId(ORDER_ID)
                    .amount(AMOUNT)
                    .build();
            given(paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID))
                    .willReturn(Optional.of(pendingPayment));

            // when
            Payment result = paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId());

            // then
            assertThat(result).isNotNull();
            assertThat(result.getOrderId()).isEqualTo(ORDER_ID);
        }

        @Test
        @DisplayName("실패 : 주문 없음 -> PaymentNotFoundException")
        void findAndValidate_OrderNotFound_ThrowsException() {
            // given
            given(paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID))
                    .willReturn(Optional.empty());

            // when, then
            assertThatThrownBy(() ->
                    paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId())
            ).isInstanceOf(PaymentNotFoundException.class);
        }
    }
}
