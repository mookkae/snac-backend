package com.ureca.snac.money.service;

import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.exception.PaymentAlreadyProcessedPaymentException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.payment.service.PaymentRecoveryService;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * MoneyService 트랜잭션 경계 테스트
 * <p>
 * DB 실패 시 복구 서비스 호출 확인
 * 이미 처리된 Payment 에 대한 멱등성 검증
 * 외부 API 성공 후 내부 실패 시 복구 로직 동작
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MoneyService 트랜잭션 경계 테스트")
class MoneyServiceTransactionTest {

    @InjectMocks
    private MoneyServiceImpl moneyService;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentGatewayAdapter paymentGatewayAdapter;

    @Mock
    private MoneyDepositor moneyDepositor;

    @Mock
    private PaymentRecoveryService paymentRecoveryService;

    private Member member;
    private Payment pendingPayment;
    private TossConfirmResponse tossConfirmResponse;

    private static final String ORDER_ID = "snac_order_test_123";
    private static final String PAYMENT_KEY = "test_payment_key";
    private static final Long AMOUNT = 10000L;
    private static final String EMAIL = "test@snac.com";

    @BeforeEach
    void setUp() {
        member = MemberFixture.createMember(1L);
        pendingPayment = PaymentFixture.builder()
                .member(member)
                .orderId(ORDER_ID)
                .amount(AMOUNT)
                .build();
        tossConfirmResponse = new TossConfirmResponse(
                PAYMENT_KEY,
                "카드",
                OffsetDateTime.now()
        );
    }

    @Test
    @DisplayName("정상 : 토스 승인 -> DB 처리 모두 성공")
    void processRechargeSuccess_HappyPath() {
        // given
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID))
                .willReturn(Optional.of(pendingPayment));
        given(paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willReturn(tossConfirmResponse);

        // when
        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL);

        // then
        verify(paymentGatewayAdapter, times(1))
                .confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);
        verify(moneyDepositor, times(1))
                .deposit(any(Payment.class), any(Member.class), any(TossConfirmResponse.class));
        verify(paymentRecoveryService, never())
                .processInternalFailure(any(), any());
    }

    @Test
    @DisplayName("정상 : 내부 DB 실패 시 복구 서비스 호출")
    void processRechargeSuccess_DBFailure_CallsRecoveryService() {
        // given
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID))
                .willReturn(Optional.of(pendingPayment));
        given(paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willReturn(tossConfirmResponse);

        // DB 저장 실패 런타임 예외
        RuntimeException dbException = new RuntimeException("DB Connection Failed");
        doThrow(dbException).when(moneyDepositor)
                .deposit(any(Payment.class), any(Member.class), any(TossConfirmResponse.class));

        // when, then
        assertThatThrownBy(() ->
                moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
        ).isInstanceOf(Exception.class);

        // 복구 서비스 호출 확인
        verify(paymentRecoveryService, times(1))
                .processInternalFailure(any(Payment.class), any(Exception.class));
    }

    @Test
    @DisplayName("정상 : 이미 처리된 Payment는 예외")
    void processRechargeSuccess_AlreadyProcessed_ThrowsException() {
        // given : SUCCESS 상태 Payment
        Payment processedPayment = PaymentFixture.createSuccessPayment(member);

        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID))
                .willReturn(Optional.of(processedPayment));

        // when , then
        assertThatThrownBy(() ->
                moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
        ).isInstanceOf(PaymentAlreadyProcessedPaymentException.class);

        // 외부 API 호출 안 함
        verify(paymentGatewayAdapter, never())
                .confirmPayment(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("정상 : 다른 회원의 Payment 접근 시 예외")
    void processRechargeSuccess_WrongOwner_ThrowsException() {
        // given : 다른 회원의 Payment
        Member anotherMember = MemberFixture.createMember(999L);
        Payment anotherMembersPayment = PaymentFixture.builder()
                .member(anotherMember)
                .orderId(ORDER_ID)
                .amount(AMOUNT)
                .build();

        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID))
                .willReturn(Optional.of(anotherMembersPayment));

        // when, then
        assertThatThrownBy(() ->
                moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
        ).isInstanceOf(Exception.class);

        // 외부 API 호출 안 함
        verify(paymentGatewayAdapter, never())
                .confirmPayment(anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("정상 : 금액 불일치 시 예외")
    void processRechargeSuccess_AmountMismatch_ThrowsException() {
        // given : 금액이 다른 Payment
        Payment differentAmountPayment = PaymentFixture.builder()
                .member(member)
                .orderId(ORDER_ID)
                .amount(5000L) // 요청 금액과 다름
                .build();

        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID))
                .willReturn(Optional.of(differentAmountPayment));

        // when, then
        assertThatThrownBy(() ->
                moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
        ).isInstanceOf(Exception.class);

        // 외부 API 호출 안 함
        verify(paymentGatewayAdapter, never()).confirmPayment(anyString(), anyString(), anyLong());
    }
}
