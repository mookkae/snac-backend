package com.ureca.snac.payment.listener;

import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import com.ureca.snac.payment.service.PaymentAlertService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.mockito.BDDMockito.doThrow;
import static org.mockito.BDDMockito.verify;

/**
 * CriticalPaymentFailureAlertListener 단위 테스트
 * handleCriticalFailure: 치명적 실패 이벤트 처리 및 Slack 알림 발송
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CriticalPaymentFailureAlertListenerTest 단위 테스트")
class CriticalPaymentFailureAlertListenerTest {

    @InjectMocks
    private CriticalPaymentFailureAlertListener listener;

    @Mock
    private PaymentAlertService paymentAlertService;

    private static final Long PAYMENT_ID = 1L;
    private static final Long MEMBER_ID = 100L;
    private static final Long AMOUNT = 10000L;
    private static final String ORDER_ID = "snac_order_test_123";
    private static final String PAYMENT_KEY = "test_payment_key";

    @Nested
    @DisplayName("handleCriticalFailure 메서드")
    class HandleCriticalFailureTest {

        @Nested
        @DisplayName("AutoCancelFailureEvent 처리")
        class AutoCancelFailureEventTest {

            @Test
            @DisplayName("정상 : AutoCancelFailureEvent 수신 시 alertAutoCancelFailure 호출")
            void handleCriticalFailure_AutoCancelEvent_CallsAlertService() {
                // given
                AutoCancelFailureEvent event = new AutoCancelFailureEvent(
                        PAYMENT_ID,
                        MEMBER_ID,
                        AMOUNT,
                        ORDER_ID,
                        PAYMENT_KEY,
                        "DB Connection Failed",
                        "Toss Cancel API Failed"
                );

                // when
                listener.handleCriticalFailure(event);

                // then
                verify(paymentAlertService).alertAutoCancelFailure(event);
            }

            @Test
            @DisplayName("예외 처리 : alertAutoCancelFailure 실패해도 예외 전파 않음")
            void handleCriticalFailure_AutoCancelEvent_SwallowsException() {
                // given
                AutoCancelFailureEvent event = new AutoCancelFailureEvent(
                        PAYMENT_ID,
                        MEMBER_ID,
                        AMOUNT,
                        ORDER_ID,
                        PAYMENT_KEY,
                        "DB Error",
                        "Cancel Error"
                );
                doThrow(new RuntimeException("Slack API Failed"))
                        .when(paymentAlertService).alertAutoCancelFailure(event);

                // when & then: 예외 발생하지 않음
                listener.handleCriticalFailure(event);

                // verify: 호출은 됨
                verify(paymentAlertService).alertAutoCancelFailure(event);
            }
        }

        @Nested
        @DisplayName("CompensationFailureEvent 처리")
        class CompensationFailureEventTest {

            @Test
            @DisplayName("정상 : CompensationFailureEvent 수신 시 alertCompensationFailure 호출")
            void handleCriticalFailure_CompensationEvent_CallsAlertService() {
                // given
                CompensationFailureEvent event = new CompensationFailureEvent(
                        PAYMENT_ID,
                        MEMBER_ID,
                        AMOUNT,
                        ORDER_ID,
                        PAYMENT_KEY,
                        "고객 요청 취소",
                        OffsetDateTime.now(),
                        "Original DB Error",
                        "Compensation DB Error"
                );

                // when
                listener.handleCriticalFailure(event);

                // then
                verify(paymentAlertService).alertCompensationFailure(event);
            }

            @Test
            @DisplayName("예외 처리 : alertCompensationFailure 실패해도 예외 전파 않음")
            void handleCriticalFailure_CompensationEvent_SwallowsException() {
                // given
                CompensationFailureEvent event = new CompensationFailureEvent(
                        PAYMENT_ID,
                        MEMBER_ID,
                        AMOUNT,
                        ORDER_ID,
                        PAYMENT_KEY,
                        "취소 사유",
                        OffsetDateTime.now(),
                        "Original Error",
                        "Compensation Error"
                );
                doThrow(new RuntimeException("Slack API Failed"))
                        .when(paymentAlertService).alertCompensationFailure(event);

                // when & then: 예외 발생하지 않음
                listener.handleCriticalFailure(event);

                // verify: 호출은 됨
                verify(paymentAlertService).alertCompensationFailure(event);
            }
        }
    }
}
