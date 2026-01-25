package com.ureca.snac.payment.service;

import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.dto.PaymentFailureRequest;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.PaymentAlreadyProcessedPaymentException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import com.ureca.snac.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * PaymentService 트랜잭션 경계 및 보상 로직 테스트
 * <p>
 * 결제 취소 시 DB 실패 -> 보상 처리 동작
 * 결제 실패 처리 시 멱등성 검증
 * 동시 실패 요청에 대한 처리
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentService 트랜잭션 경계 테스트")
class PaymentServiceTransactionTest {

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
        cancelResponse = new PaymentCancelResponse(
                PAYMENT_KEY,
                AMOUNT,
                OffsetDateTime.now(),
                CANCEL_REASON
        );
    }

    // ==================== cancelPayment 테스트 ====================

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
    @DisplayName("성공 : 토스 취소 + DB 실패 시 보상 처리 호출")
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
    @DisplayName("잔액 부족 시 취소 불가")
    void cancelPayment_InsufficientBalance_ThrowsException() {
        // given : 잔액이 결제 금액보다 적음
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY))
                .willReturn(Optional.of(successPayment));
        given(walletService.getMoneyBalance(member.getId())).willReturn(1000L); // 부족

        // when. then
        assertThatThrownBy(() ->
                paymentService.cancelPayment(PAYMENT_KEY, CANCEL_REASON, EMAIL)
        ).isInstanceOf(Exception.class);

        // 외부 API 호출 안 함
        verify(paymentGatewayAdapter, never())
                .cancelPayment(anyString(), anyString());
    }

    // ==================== processPaymentFailure 테스트 ====================

    @Test
    @DisplayName("정상 : PENDING -> CANCELED로 변경")
    void processPaymentFailure_HappyPath() {
        // given
        Payment pendingPayment = PaymentFixture.builder()
                .member(member)
                .orderId(ORDER_ID)
                .amount(AMOUNT)
                .build();

        PaymentFailureRequest request = new PaymentFailureRequest(
                "INVALID_CARD_INFO",
                "카드 정보가 유효하지 않습니다",
                ORDER_ID,
                null
        );

        given(paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID))
                .willReturn(Optional.of(pendingPayment));

        // when
        assertThatCode(() -> paymentService.processPaymentFailure(request))
                .doesNotThrowAnyException();

        // then
        verify(paymentRepository, times(1)).save(any(Payment.class));
    }

    @Test
    @DisplayName("멱등성 : 이미 처리된 Payment에 실패 요청 시 예외")
    void processPaymentFailure_AlreadyProcessed_ThrowsException() {
        // given : SUCCESS 상태의 Payment
        PaymentFailureRequest request = new PaymentFailureRequest(
                "INVALID_CARD_INFO",
                "카드 정보가 유효하지 않습니다",
                ORDER_ID,
                null
        );

        given(paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID))
                .willReturn(Optional.of(successPayment));

        // when, then
        assertThatThrownBy(() -> paymentService.processPaymentFailure(request))
                .isInstanceOf(PaymentAlreadyProcessedPaymentException.class);

        // DB 저장 안 함
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    @DisplayName("실패 : CANCELED 상태 Payment에 중복 실패 요청 시 예외")
    void processPaymentFailure_AlreadyCanceled_ThrowsException() {
        // given : CANCELED 상태의 Payment
        Payment canceledPayment = PaymentFixture.builder()
                .member(member)
                .orderId(ORDER_ID)
                .status(PaymentStatus.CANCELED)
                .build();

        PaymentFailureRequest request = new PaymentFailureRequest(
                "INVALID_CARD_INFO",
                "카드 정보가 유효하지 않습니다",
                ORDER_ID,
                null
        );

        given(paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID))
                .willReturn(Optional.of(canceledPayment));

        // when, then
        assertThatThrownBy(() -> paymentService.processPaymentFailure(request))
                .isInstanceOf(PaymentAlreadyProcessedPaymentException.class);

        verify(paymentRepository, never()).save(any(Payment.class));
    }
}
