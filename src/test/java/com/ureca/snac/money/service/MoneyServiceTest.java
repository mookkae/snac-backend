package com.ureca.snac.money.service;

import com.ureca.snac.common.exception.InternalServerException;
import com.ureca.snac.infra.exception.TossInvalidCardInfoException;
import com.ureca.snac.infra.exception.TossNotEnoughBalanceException;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.money.dto.MoneyRechargeSuccessResponse;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.exception.AlreadyCanceledPaymentException;
import com.ureca.snac.payment.exception.PaymentAlreadySuccessException;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.payment.port.out.exception.GatewayTransientException;
import com.ureca.snac.payment.service.PaymentService;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentCancelResponseFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
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

    private MoneyServiceImpl moneyService;
    private SimpleMeterRegistry meterRegistry;

    @Mock
    private PaymentService paymentService;

    @Mock
    private PaymentGatewayPort paymentGatewayPort;

    @Mock
    private MoneyDepositorRetryFacade moneyDepositorRetryFacade;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private Member member;
    private Payment pendingPayment;
    private PaymentConfirmResult confirmResult;
    private PaymentCancelResponse cancelResponse;
    private com.ureca.snac.payment.port.out.dto.PaymentCancelResult cancelResult;

    private static final String ORDER_ID = "snac_order_test_123";
    private static final String PAYMENT_KEY = "test_payment_key";
    private static final Long AMOUNT = 10000L;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        moneyService = new MoneyServiceImpl(
                paymentService, paymentGatewayPort,
                moneyDepositorRetryFacade, eventPublisher, meterRegistry
        );
        member = MemberFixture.createMember(1L);
        pendingPayment = PaymentFixture.builder()
                .id(1L)
                .member(member)
                .orderId(ORDER_ID)
                .amount(AMOUNT)
                .build();
        confirmResult = new PaymentConfirmResult(PAYMENT_KEY, "카드", java.time.OffsetDateTime.now());
        cancelResponse = PaymentCancelResponseFixture.create(PAYMENT_KEY, AMOUNT, "Auto-cancel");
    }

    @Nested
    @DisplayName("prepareRecharge 메서드")
    class PrepareRechargeTest {

        @Test
        @DisplayName("성공 : Payment 생성 + MoneyRechargePreparedResponse 반환")
        void prepareRecharge_HappyPath() {
            // given
            MoneyRechargeRequest request = new MoneyRechargeRequest(AMOUNT);
            Payment createdPayment = PaymentFixture.builder()
                    .member(member)
                    .orderId(ORDER_ID)
                    .amount(AMOUNT)
                    .build();

            given(paymentService.preparePayment(member, AMOUNT)).willReturn(createdPayment);

            // when
            MoneyRechargePreparedResponse response = moneyService.prepareRecharge(request, member);

            // then
            assertThat(response).isNotNull();
            assertThat(response.orderId()).isEqualTo(ORDER_ID);
            assertThat(response.amount()).isEqualTo(AMOUNT);
            assertThat(response.customerEmail()).isEqualTo(member.getEmail());
            verify(paymentService, times(1)).preparePayment(member, AMOUNT);
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
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willReturn(pendingPayment);
                given(paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willReturn(confirmResult);

                // when
                moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId());

                // then
                verify(paymentGatewayPort, times(1))
                        .confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);
                verify(moneyDepositorRetryFacade, times(1))
                        .deposit(anyLong(), anyLong(), any(PaymentConfirmResult.class));
                verify(paymentGatewayPort, never())
                        .cancelPayment(anyString(), anyString());
            }

            @Test
            @DisplayName("멱등성 복구 : 토스 기승인 건(클라이언트 재요청) 감지 시 조회 API로 복구하여 성공 처리")
            void processRechargeSuccess_AlreadyProcessedByToss_RecoversUsingInquiry() {
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willReturn(pendingPayment);
                
                // 1. 토스가 이미 처리됨 (재요청)
                given(paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new PaymentAlreadySuccessException());
                
                // 2. 조회 API로 상태 확인
                com.ureca.snac.payment.port.out.dto.PaymentInquiryResult inquiryResult = 
                        new com.ureca.snac.payment.port.out.dto.PaymentInquiryResult(
                                com.ureca.snac.payment.port.out.dto.GatewayPaymentStatus.DONE,
                                PAYMENT_KEY, ORDER_ID, AMOUNT, "카드", java.time.OffsetDateTime.now());
                given(paymentGatewayPort.inquirePaymentByOrderId(ORDER_ID)).willReturn(inquiryResult);

                // when
                moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId());

                // then
                verify(paymentGatewayPort).confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);
                verify(paymentGatewayPort).inquirePaymentByOrderId(ORDER_ID);
                verify(moneyDepositorRetryFacade).deposit(anyLong(), anyLong(), any(PaymentConfirmResult.class));
                verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());
            }
        }

        @Nested
        @DisplayName("예외 처리")
        class ExceptionTest {

            @Test
            @DisplayName("멱등성 : 이미 처리된 Payment는 예외")
            void processRechargeSuccess_AlreadyProcessed_ThrowsException() {
                // given : PaymentService가 이미 처리된 Payment에 대해 예외 발생
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willThrow(new PaymentAlreadySuccessException());

                // when, then
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId())
                ).isInstanceOf(PaymentAlreadySuccessException.class);

                // 외부 API 호출 안 함
                verify(paymentGatewayPort, never())
                        .confirmPayment(anyString(), anyString(), anyLong());
            }

            @Test
            @DisplayName("예외 : 다른 회원의 Payment 접근 시 예외")
            void processRechargeSuccess_WrongOwner_ThrowsException() {
                // given : PaymentService가 소유자 불일치 시 예외 발생
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willThrow(new IllegalArgumentException("Payment owner mismatch"));

                // when, then
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId())
                ).isInstanceOf(IllegalArgumentException.class);

                // 외부 API 호출 안 함
                verify(paymentGatewayPort, never())
                        .confirmPayment(anyString(), anyString(), anyLong());
            }

            @Test
            @DisplayName("예외 : 금액 불일치 시 예외")
            void processRechargeSuccess_AmountMismatch_ThrowsException() {
                // given : PaymentService가 금액 불일치 시 예외 발생
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willThrow(new IllegalArgumentException("Amount mismatch"));

                // when, then
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId())
                ).isInstanceOf(IllegalArgumentException.class);

                // 외부 API 호출 안 함
                verify(paymentGatewayPort, never())
                        .confirmPayment(anyString(), anyString(), anyLong());
            }

            @Test
            @DisplayName("예외 : Toss 재시도 가능 에러 시 예외 전파")
            void shouldPropagateRetryableExceptionWithoutCancel() {
                // given: Toss에서 일시적 오류 (재시도 실패 후 전파됨)
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willReturn(pendingPayment);
                given(paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new GatewayTransientException(new RuntimeException("service unavailable")));

                // when & then: 예외 전파
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId())
                ).isInstanceOf(GatewayTransientException.class);

                // Toss 승인 실패 -> 취소 불필요
                verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());
            }

            @Test
            @DisplayName("멱등성 : deposit에서 SUCCESS 상태 감지 시 성공 응답 반환 (Toss 승인 후)")
            void processRechargeSuccess_DepositDetectsAlreadySuccess_ReturnsSuccessResponse() {
                // given: Toss 승인 성공 후, deposit에서 이미 SUCCESS임을 감지
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willReturn(pendingPayment);
                given(paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willReturn(confirmResult);
                doThrow(new PaymentAlreadySuccessException())
                        .when(moneyDepositorRetryFacade).deposit(any(), any(), any());

                // when: 예외 없이 성공 응답 반환
                MoneyRechargeSuccessResponse response =
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId());

                // then: Auto-Cancel 미호출, 성공 응답
                assertThat(response.orderId()).isEqualTo(ORDER_ID);
                verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());
            }
        }

        @Nested
        @DisplayName("Auto-Cancel 동작")
        class AutoCancelTest {

            @Test
            @DisplayName("정상 : 내부 DB 실패 시 Auto-Cancel 호출")
            void processRechargeSuccess_DBFailure_CallsAutoCancel() {
                // given
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willReturn(pendingPayment);
                given(paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willReturn(confirmResult);
                given(paymentGatewayPort.cancelPayment(anyString(), anyString()))
                        .willReturn(cancelResult);

                // DB 저장 실패 런타임 예외
                RuntimeException dbException = new RuntimeException("DB Connection Failed");
                doThrow(dbException).when(moneyDepositorRetryFacade)
                        .deposit(anyLong(), anyLong(), any(PaymentConfirmResult.class));

                // when, then
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId())
                ).isInstanceOf(Exception.class);

                // Auto-Cancel 호출 확인 (Toss 승인 성공 후 DB 실패 -> 자동 취소)
                verify(paymentGatewayPort, times(1))
                        .cancelPayment(eq(PAYMENT_KEY), anyString());
            }

            @Test
            @DisplayName("Auto-Cancel 불필요 : Toss 승인 실패(카드 오류) 시")
            void shouldNotCancelWhenConfirmPaymentFails_InvalidCard() {
                // given: Toss에서 카드 오류로 승인 거절
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willReturn(pendingPayment);
                given(paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossInvalidCardInfoException());

                // when & then: 예외 발생, cancelPayment 호출 안 됨
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId())
                ).isInstanceOf(TossInvalidCardInfoException.class);

                // Toss 승인 실패 -> 취소 불필요 (돈 안 빠짐)
                verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());
            }

            @Test
            @DisplayName("Auto-Cancel 불필요 : Toss 승인 실패(잔액 부족) 시")
            void shouldNotCancelWhenConfirmPaymentFails_NotEnoughBalance() {
                // given: Toss에서 잔액 부족으로 승인 거절
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willReturn(pendingPayment);
                given(paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossNotEnoughBalanceException());

                // when & then: 예외 발생, cancelPayment 호출 안 됨
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId())
                ).isInstanceOf(TossNotEnoughBalanceException.class);

                // Toss 승인 실패 -> 취소 불필요 (돈 안 빠짐)
                verify(paymentGatewayPort, never()).cancelPayment(anyString(), anyString());
            }

            @Test
            @DisplayName("예외 : deposit에서 CANCELED 상태 감지 -> Auto-Cancel 호출")
            void processRechargeSuccess_WhenCanceled_ShouldTriggerAutoCancel() {
                // given: Toss 승인 성공, deposit에서 CANCELED 상태로 AlreadyCanceledPaymentException 발생
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willReturn(pendingPayment);
                given(paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willReturn(confirmResult);
                doThrow(new AlreadyCanceledPaymentException())
                        .when(moneyDepositorRetryFacade).deposit(any(), any(), any());

                // when & then: InternalServerException 발생 + Toss Auto-Cancel 호출
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId())
                ).isInstanceOf(InternalServerException.class);

                verify(paymentGatewayPort, times(1)).cancelPayment(eq(PAYMENT_KEY), anyString());
            }

            @Test
            @DisplayName("대사 스케줄러 경쟁 조건 : deposit CANCELED + Toss 이미 취소 -> Critical Alert 미발행")
            void processRechargeSuccess_WhenDepositCanceledAndTossAlreadyCanceled_NoAlert() {
                // given: Toss 승인 성공, deposit에서 CANCELED 감지 (대사 스케줄러가 먼저 처리),
                // Toss cancelPayment도 AlreadyCanceledPaymentException (대사 스케줄러가 Toss도 취소 완료)
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willReturn(pendingPayment);
                given(paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willReturn(confirmResult);
                doThrow(new AlreadyCanceledPaymentException())
                        .when(moneyDepositorRetryFacade).deposit(any(), any(), any());
                given(paymentGatewayPort.cancelPayment(anyString(), anyString()))
                        .willThrow(new AlreadyCanceledPaymentException());

                // when & then: InternalServerException 발생
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId())
                ).isInstanceOf(InternalServerException.class);

                // Toss + 로컬 DB 모두 정상 취소 상태 -> Critical Alert 미발행
                verify(eventPublisher, never()).publishEvent(any(AutoCancelFailureEvent.class));
            }

            @Test
            @DisplayName("Auto-Cancel 실패 시 AutoCancelFailureEvent 발행")
            void shouldPublishEventWhenAutoCancelFails() {
                // given: Toss 승인 성공, DB 저장 실패, Auto-Cancel도 실패
                // given
                given(paymentService.findAndValidateForConfirmation(ORDER_ID, AMOUNT, member.getId()))
                        .willReturn(pendingPayment);
                given(paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willReturn(confirmResult);

                // DB 저장 실패
                doThrow(new RuntimeException("DB Connection Failed"))
                        .when(moneyDepositorRetryFacade).deposit(any(), any(), any());

                // Auto-Cancel도 실패
                given(paymentGatewayPort.cancelPayment(anyString(), anyString()))
                        .willThrow(new GatewayTransientException(new RuntimeException("service unavailable")));

                // when & then: 예외 발생
                assertThatThrownBy(() ->
                        moneyService.processRechargeSuccess(PAYMENT_KEY, ORDER_ID, AMOUNT, member.getId())
                ).isInstanceOf(Exception.class);

                // Critical: Auto-Cancel 실패 -> AutoCancelFailureEvent 발행
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
