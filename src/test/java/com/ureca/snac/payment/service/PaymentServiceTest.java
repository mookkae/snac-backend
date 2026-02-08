package com.ureca.snac.payment.service;

import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentCancelResponseFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import com.ureca.snac.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private PaymentServiceImpl paymentService;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGatewayAdapter paymentGatewayAdapter;

    @Mock
    private PaymentInternalService paymentInternalService;

    @Mock
    private WalletService walletService;

    private Member member;
    private Payment successPayment;
    private PaymentCancelResponse cancelResponse;

    private static final String PAYMENT_KEY = "test_payment_key";
    private static final String ORDER_ID = "snac_order_test_123";
    private static final Long AMOUNT = 10000L;
    private static final String EMAIL = "test@snac.com";
    private static final String CANCEL_REASON = "고객 요청";

    @BeforeEach
    void setUp() {
        member = MemberFixture.createMember(1L);
        successPayment = PaymentFixture.createSuccessPayment(member);
        cancelResponse = PaymentCancelResponseFixture.create(PAYMENT_KEY, AMOUNT, CANCEL_REASON);
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
            given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
            given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                    .willReturn(Optional.of(successPayment));
            given(walletService.getMoneyBalance(member.getId())).willReturn(AMOUNT);
            given(paymentGatewayAdapter.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                    .willReturn(cancelResponse);

            // when
            paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, EMAIL);

            // then
            verify(paymentGatewayAdapter, times(1))
                    .cancelPayment(PAYMENT_KEY, CANCEL_REASON);
            verify(paymentInternalService, times(1))
                    .processCancellationInDB(any(Payment.class), any(Member.class), any(PaymentCancelResponse.class));
        }

        @Test
        @DisplayName("보상 처리 : 토스 취소 성공 + DB 실패 시 보상 처리 호출")
        void cancelPayment_DBFailure_CompensationTriggered() {
            // given
            given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
            given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                    .willReturn(Optional.of(successPayment));
            given(walletService.getMoneyBalance(member.getId())).willReturn(AMOUNT);
            given(paymentGatewayAdapter.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                    .willReturn(cancelResponse);

            RuntimeException dbException = new RuntimeException("DB Connection Failed");
            doThrow(dbException).when(paymentInternalService)
                    .processCancellationInDB(any(Payment.class), any(Member.class), any(PaymentCancelResponse.class));

            // when, then
            assertThatThrownBy(() ->
                    paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, EMAIL)
            ).isInstanceOf(RuntimeException.class)
                    .hasMessage("DB Connection Failed");

            // 보상 처리 호출 확인 (PaymentInternalService로 위임)
            verify(paymentInternalService, times(1))
                    .compensateCancellationFailure(
                            any(Payment.class),
                            any(Member.class),
                            any(PaymentCancelResponse.class),
                            any(Exception.class)
                    );
        }

        @Test
        @DisplayName("예외 : 잔액 부족 시 취소 불가")
        void cancelPayment_InsufficientBalance_ThrowsException() {
            // given : 잔액이 결제 금액보다 적음
            given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
            given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                    .willReturn(Optional.of(successPayment));
            given(walletService.getMoneyBalance(member.getId())).willReturn(1000L); // 부족

            // when, then
            assertThatThrownBy(() ->
                    paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, EMAIL)
            ).isInstanceOf(Exception.class);

            // 외부 API 호출 안 함
            verify(paymentGatewayAdapter, never())
                    .cancelPayment(anyString(), anyString());
        }

        @Test
        @DisplayName("실패 : 회원 없음 -> MemberNotFoundException")
        void cancelPayment_MemberNotFound_ThrowsException() {
            // given
            given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

            // when, then
            assertThatThrownBy(() ->
                    paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, EMAIL)
            ).isInstanceOf(MemberNotFoundException.class);

            verify(paymentGatewayAdapter, never()).cancelPayment(anyString(), anyString());
        }

        @Test
        @DisplayName("실패 : 결제 없음 -> PaymentNotFoundException")
        void cancelPayment_PaymentNotFound_ThrowsException() {
            // given
            given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
            given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                    .willReturn(Optional.empty());

            // when, then
            assertThatThrownBy(() ->
                    paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, EMAIL)
            ).isInstanceOf(PaymentNotFoundException.class);

            verify(paymentGatewayAdapter, never()).cancelPayment(anyString(), anyString());
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
            Payment result = paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member);

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
                    paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member)
            ).isInstanceOf(PaymentNotFoundException.class);
        }
    }
}
