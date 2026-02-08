package com.ureca.snac.money.service;

import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.TossErrorCode;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.infra.fixture.TossResponseFixture;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.exception.PaymentAlreadyProcessedPaymentException;
import com.ureca.snac.payment.exception.TossInvalidCardInfoException;
import com.ureca.snac.payment.exception.TossNotEnoughBalanceException;
import com.ureca.snac.payment.exception.TossRetryableException;
import com.ureca.snac.payment.service.PaymentService;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentCancelResponseFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * MoneyService 단위 테스트
 * <p>
 * 충전 성공 처리 (토스 승인 + DB 처리),Auto-Cancel (토스 성공 후 DB 실패 시 자동 취소)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MoneyServiceTest 단위 테스트")
class MoneyServiceTest {

    @InjectMocks
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
    private PaymentCancelResponse cancelResponse;

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
        tossConfirmResponse = TossResponseFixture.createConfirmResponse(PAYMENT_KEY);
        cancelResponse = PaymentCancelResponseFixture.create(PAYMENT_KEY, AMOUNT, "Auto-cancel");
    }

    @Nested
    @DisplayName("prepareRecharge 메서드")
    class PrepareRechargeTest {

        @Test
        @DisplayName("성공 : Member 조회 + Payment 생성 + MoneyRechargePreparedResponse 반환")
        void prepareRecharge_HappyPath() {
            // given
            MoneyRechargeRequest request = new MoneyRechargeRequest(AMOUNT);
            Payment createdPayment = PaymentFixture.builder()
                    .member(member)
                    .orderId(ORDER_ID)
                    .amount(AMOUNT)
                    .build();

            given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
            given(paymentService.preparePayment(member, AMOUNT)).willReturn(createdPayment);

            // when
            MoneyRechargePreparedResponse response = moneyService.prepareRecharge(request, EMAIL);

            // then
            assertThat(response).isNotNull();
            assertThat(response.orderId()).isEqualTo(ORDER_ID);
            assertThat(response.amount()).isEqualTo(AMOUNT);
            assertThat(response.customerEmail()).isEqualTo(member.getEmail());
            verify(paymentService, times(1)).preparePayment(member, AMOUNT);
        }

        @Test
        @DisplayName("실패 : 회원 없음 → MemberNotFoundException")
        void prepareRecharge_MemberNotFound_ThrowsException() {
            // given
            MoneyRechargeRequest request = new MoneyRechargeRequest(AMOUNT);
            given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

            // when, then
            assertThatThrownBy(() -> moneyService.prepareRecharge(request, EMAIL))
                    .isInstanceOf(MemberNotFoundException.class);

            verify(paymentService, never()).preparePayment(any(), anyLong());
        }
    }

    @Nested
    @DisplayName("processRechargeSuccess 메서드")
    class ProcessRechargeSuccessTest {

        @Nested
        @DisplayName("정상 처리")
        class HappyPathTest {

            @Test
            @DisplayName("정상 : 토스 승인 -> DB 처리 모두 성공")
            void processRechargeSuccess_HappyPath() {
                // given
                given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                        .willReturn(pendingPayment);
                given(paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willReturn(tossConfirmResponse);

                // when
                moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL);

                // then
                verify(paymentGatewayAdapter, times(1))
                        .confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);
                verify(moneyDepositor, times(1))
                        .deposit(any(Payment.class), any(Member.class), any(TossConfirmResponse.class));
                verify(paymentGatewayAdapter, never())
                        .cancelPayment(anyString(), anyString());
            }
        }

        @Nested
        @DisplayName("예외 처리")
        class ExceptionTest {

            @Test
            @DisplayName("멱등성 : 이미 처리된 Payment는 예외")
            void processRechargeSuccess_AlreadyProcessed_ThrowsException() {
                // given : PaymentService가 이미 처리된 Payment에 대해 예외 발생
                given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                        .willThrow(new PaymentAlreadyProcessedPaymentException());

                // when, then
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
                ).isInstanceOf(PaymentAlreadyProcessedPaymentException.class);

                // 외부 API 호출 안 함
                verify(paymentGatewayAdapter, never())
                        .confirmPayment(anyString(), anyString(), anyLong());
            }

            @Test
            @DisplayName("예외 : 다른 회원의 Payment 접근 시 예외")
            void processRechargeSuccess_WrongOwner_ThrowsException() {
                // given : PaymentService가 소유자 불일치 시 예외 발생
                given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                        .willThrow(new IllegalArgumentException("Payment owner mismatch"));

                // when, then
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
                ).isInstanceOf(IllegalArgumentException.class);

                // 외부 API 호출 안 함
                verify(paymentGatewayAdapter, never())
                        .confirmPayment(anyString(), anyString(), anyLong());
            }

            @Test
            @DisplayName("예외 : 금액 불일치 시 예외")
            void processRechargeSuccess_AmountMismatch_ThrowsException() {
                // given : PaymentService가 금액 불일치 시 예외 발생
                given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                        .willThrow(new IllegalArgumentException("Amount mismatch"));

                // when, then
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
                ).isInstanceOf(IllegalArgumentException.class);

                // 외부 API 호출 안 함
                verify(paymentGatewayAdapter, never())
                        .confirmPayment(anyString(), anyString(), anyLong());
            }

            @Test
            @DisplayName("실패 : 회원 없음 → MemberNotFoundException")
            void processRechargeSuccess_MemberNotFound_ThrowsException() {
                // given
                given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

                // when, then
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
                ).isInstanceOf(MemberNotFoundException.class);

                verify(paymentService, never()).findAndValidateForConfirmation(anyString(), anyLong(), any());
                verify(paymentGatewayAdapter, never()).confirmPayment(anyString(), anyString(), anyLong());
            }

            @Test
            @DisplayName("예외 : Toss 재시도 가능 에러 시 예외 전파")
            void shouldPropagateRetryableExceptionWithoutCancel() {
                // given: Toss에서 일시적 오류 (재시도 실패 후 전파됨)
                given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                        .willReturn(pendingPayment);
                given(paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossRetryableException(TossErrorCode.SERVICE_UNAVAILABLE));

                // when & then: 예외 전파
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
                ).isInstanceOf(TossRetryableException.class);

                // Toss 승인 실패 → 취소 불필요
                verify(paymentGatewayAdapter, never()).cancelPayment(anyString(), anyString());
            }
        }

        @Nested
        @DisplayName("Auto-Cancel 동작")
        class AutoCancelTest {

            @Test
            @DisplayName("정상 : 내부 DB 실패 시 Auto-Cancel 호출")
            void processRechargeSuccess_DBFailure_CallsAutoCancel() {
                // given
                given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                        .willReturn(pendingPayment);
                given(paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willReturn(tossConfirmResponse);
                given(paymentGatewayAdapter.cancelPayment(anyString(), anyString()))
                        .willReturn(cancelResponse);

                // DB 저장 실패 런타임 예외
                RuntimeException dbException = new RuntimeException("DB Connection Failed");
                doThrow(dbException).when(moneyDepositor)
                        .deposit(any(Payment.class), any(Member.class), any(TossConfirmResponse.class));

                // when, then
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
                ).isInstanceOf(Exception.class);

                // Auto-Cancel 호출 확인 (Toss 승인 성공 후 DB 실패 → 자동 취소)
                verify(paymentGatewayAdapter, times(1))
                        .cancelPayment(eq(PAYMENT_KEY), anyString());
            }

            @Test
            @DisplayName("Auto-Cancel 불필요 : Toss 승인 실패(카드 오류) 시")
            void shouldNotCancelWhenConfirmPaymentFails_InvalidCard() {
                // given: Toss에서 카드 오류로 승인 거절
                given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                        .willReturn(pendingPayment);
                given(paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossInvalidCardInfoException());

                // when & then: 예외 발생, cancelPayment 호출 안 됨
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
                ).isInstanceOf(TossInvalidCardInfoException.class);

                // Toss 승인 실패 → 취소 불필요 (돈 안 빠짐)
                verify(paymentGatewayAdapter, never()).cancelPayment(anyString(), anyString());
            }

            @Test
            @DisplayName("Auto-Cancel 불필요 : Toss 승인 실패(잔액 부족) 시")
            void shouldNotCancelWhenConfirmPaymentFails_NotEnoughBalance() {
                // given: Toss에서 잔액 부족으로 승인 거절
                given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                        .willReturn(pendingPayment);
                given(paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossNotEnoughBalanceException());

                // when & then: 예외 발생, cancelPayment 호출 안 됨
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
                ).isInstanceOf(TossNotEnoughBalanceException.class);

                // Toss 승인 실패 → 취소 불필요 (돈 안 빠짐)
                verify(paymentGatewayAdapter, never()).cancelPayment(anyString(), anyString());
            }

            @Test
            @DisplayName("Auto-Cancel 실패 시 AutoCancelFailureEvent 발행")
            void shouldPublishEventWhenAutoCancelFails() {
                // given: Toss 승인 성공, DB 저장 실패, Auto-Cancel도 실패
                given(memberRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member))
                        .willReturn(pendingPayment);
                given(paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willReturn(tossConfirmResponse);

                // DB 저장 실패
                doThrow(new RuntimeException("DB Connection Failed"))
                        .when(moneyDepositor).deposit(any(), any(), any());

                // Auto-Cancel도 실패
                given(paymentGatewayAdapter.cancelPayment(anyString(), anyString()))
                        .willThrow(new TossRetryableException(TossErrorCode.SERVICE_UNAVAILABLE));

                // when & then: 예외 발생
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, EMAIL)
                ).isInstanceOf(Exception.class);

                // Critical: Auto-Cancel 실패 → AutoCancelFailureEvent 발행
                ArgumentCaptor<AutoCancelFailureEvent> eventCaptor =
                        ArgumentCaptor.forClass(AutoCancelFailureEvent.class);
                verify(eventPublisher, times(1)).publishEvent(eventCaptor.capture());

                AutoCancelFailureEvent capturedEvent = eventCaptor.getValue();
                assertThat(capturedEvent.orderId()).isEqualTo(ORDER_ID);
                assertThat(capturedEvent.amount()).isEqualTo(AMOUNT);
                assertThat(capturedEvent.paymentKey()).isEqualTo(PAYMENT_KEY);
            }
        }
    }
}
