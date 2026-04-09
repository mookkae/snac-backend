package com.ureca.snac.money.service;

import com.ureca.snac.common.exception.InternalServerException;
import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.infra.fixture.TossResponseFixture;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.money.dto.MoneyRechargeSuccessResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.exception.AlreadyCanceledPaymentException;
import com.ureca.snac.payment.exception.PaymentAlreadySuccessException;
import com.ureca.snac.payment.service.PaymentService;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MoneyService 결제 상태 정합성 테스트")
class MoneyServiceIntegrityTest {

    private MoneyServiceImpl moneyService;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentGatewayAdapter paymentGatewayAdapter;

    @Mock
    private MoneyDepositor moneyDepositor;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private Member member;
    private Payment pendingPayment;
    private TossConfirmResponse tossConfirmResponse;

    private static final String ORDER_ID = "snac_order_test_123";
    private static final String PAYMENT_KEY = "test_payment_key";
    private static final Long AMOUNT = 10000L;
    private static final String EMAIL = "test@snac.com";

    @BeforeEach
    void setUp() {
        moneyService = new MoneyServiceImpl(
                memberRepository, paymentService, paymentGatewayAdapter,
                moneyDepositor, eventPublisher, new SimpleMeterRegistry()
        );
        member = MemberFixture.createMember(1L);
        pendingPayment = PaymentFixture.builder()
                .member(member)
                .orderId(ORDER_ID)
                .amount(AMOUNT)
                .build();
        tossConfirmResponse = TossResponseFixture.createConfirmResponse(PAYMENT_KEY);
    }

    @Test
    @DisplayName("성공: MoneyDepositor에서 CANCELED 상태로 인해 AlreadyCanceledPaymentException 발생 시 Auto-Cancel이 작동해야 함")
    void processRechargeSuccess_WhenCanceled_ShouldTriggerAutoCancel() {
        // given
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                .willReturn(pendingPayment);
        given(paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willReturn(tossConfirmResponse);

        // MoneyDepositor가 AlreadyCanceledPaymentException을 던짐
        doThrow(new AlreadyCanceledPaymentException())
                .when(moneyDepositor).deposit(any(), any(), any());

        // when & then
        assertThatThrownBy(() -> 
                moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
        ).isInstanceOf(InternalServerException.class);

        // Toss API 취소(Auto-Cancel)가 호출되었는지 검증
        verify(moneyDepositor, times(1)).deposit(any(), any(), any());
        verify(paymentGatewayAdapter, times(1)).cancelPayment(eq(PAYMENT_KEY), anyString());
    }

    @Test
    @DisplayName("성공: MoneyDepositor에서 PaymentAlreadySuccessException 발생 시 (SUCCESS 상태) 성공 응답을 반환함 (멱등성)")
    void processRechargeSuccess_WhenAlreadySuccess_ShouldReturnSuccessResponse() {
        // given
        given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
        given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                .willReturn(pendingPayment);
        given(paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                .willReturn(tossConfirmResponse);

        // MoneyDepositor가 SUCCESS 상태임을 알리는 예외를 던짐
        doThrow(new PaymentAlreadySuccessException())
                .when(moneyDepositor).deposit(any(), any(), any());

        // when
        MoneyRechargeSuccessResponse response = moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL);

        // then
        assertThat(response).isNotNull();
        assertThat(response.orderId()).isEqualTo(ORDER_ID);

        // 이미 성공한 결제는 취소하지 않음
        verify(paymentGatewayAdapter, never()).cancelPayment(anyString(), anyString());
    }
}
