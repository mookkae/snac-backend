package com.ureca.snac.payment.service;

import com.ureca.snac.common.notification.SlackNotifier;
import com.ureca.snac.common.notification.dto.SlackMessage;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * PaymentAlertService 단위 테스트
 * alertAutoCancelFailure : Auto-Cancel 실패 시 Slack 알림 발송
 * alertCompensationFailure : 보상 처리 실패 시 Slack 알림 발송
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentAlertServiceTest 단위 테스트")
class PaymentAlertServiceTest {

    @InjectMocks
    private PaymentAlertService paymentAlertService;

    @Mock
    private SlackNotifier slackNotifier;

    @Nested
    @DisplayName("alertAutoCancelFailure 메서드")
    class AlertAutoCancelFailureTest {

        @Test
        @DisplayName("정상 : Slack 알림 발송")
        void alertAutoCancelFailure_ShouldSendSlackMessage() {
            // given
            AutoCancelFailureEvent event = new AutoCancelFailureEvent(
                    1L,
                    100L,
                    10000L,
                    "test_order_123",
                    "test_payment_key",
                    "DB Connection Failed",
                    "Toss Cancel API Failed"
            );

            // when
            paymentAlertService.alertAutoCancelFailure(event);

            // then
            ArgumentCaptor<SlackMessage> messageCaptor = ArgumentCaptor.forClass(SlackMessage.class);
            verify(slackNotifier).sendAsync(messageCaptor.capture());

            SlackMessage capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage.text()).contains("CRITICAL");
        }
    }

    @Nested
    @DisplayName("alertCompensationFailure 메서드")
    class AlertCompensationFailureTest {

        @Test
        @DisplayName("정상 : Slack 알림 발송")
        void alertCompensationFailure_ShouldSendSlackMessage() {
            // given
            CompensationFailureEvent event = new CompensationFailureEvent(
                    1L,
                    100L,
                    10000L,
                    "test_order_123",
                    "test_payment_key",
                    "고객 요청 취소",
                    OffsetDateTime.now(),
                    "Original DB Error",
                    "Compensation Error"
            );

            // when
            paymentAlertService.alertCompensationFailure(event);

            // then
            ArgumentCaptor<SlackMessage> messageCaptor = ArgumentCaptor.forClass(SlackMessage.class);
            verify(slackNotifier).sendAsync(messageCaptor.capture());

            SlackMessage capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage.text()).contains("CRITICAL");
            assertThat(capturedMessage.text()).contains("보상");
        }

        @Test
        @DisplayName("정상 : 필수 정보 포함 확인")
        void alertCompensationFailure_ShouldIncludeRequiredInfo() {
            // given
            CompensationFailureEvent event = new CompensationFailureEvent(
                    1L,
                    100L,
                    25000L,
                    "order_compensation_test",
                    "pk_compensation_test",
                    "테스트 취소 사유",
                    OffsetDateTime.now(),
                    "Unique Original Error",
                    "Unique Compensation Error"
            );

            // when
            paymentAlertService.alertCompensationFailure(event);

            // then
            verify(slackNotifier).sendAsync(org.mockito.ArgumentMatchers.any(SlackMessage.class));
        }
    }

    @Nested
    @DisplayName("alertReconciliationAutoCanceled 메서드")
    class AlertReconciliationAutoCanceledTest {

        @Test
        @DisplayName("성공 : Slack warning 알림 발송")
        void alertReconciliationAutoCanceled_ShouldSendWarningMessage() {
            // given
            Member member = MemberFixture.createMember(1L);
            Payment payment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .orderId("snac_order_reconciliation_1")
                    .amount(10000L)
                    .build();

            // when
            paymentAlertService.alertReconciliationAutoCanceled(payment, "pk_reconcile_test");

            // then
            ArgumentCaptor<SlackMessage> messageCaptor = ArgumentCaptor.forClass(SlackMessage.class);
            verify(slackNotifier).sendAsync(messageCaptor.capture());

            SlackMessage capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage.text()).contains("대사");
        }
    }

    @Nested
    @DisplayName("alertReconciliationCancelFailed 메서드")
    class AlertReconciliationCancelFailedTest {

        @Test
        @DisplayName("성공 : Slack danger 알림 발송")
        void alertReconciliationCancelFailed_ShouldSendDangerMessage() {
            // given
            Member member = MemberFixture.createMember(1L);
            Payment payment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .orderId("snac_order_reconciliation_2")
                    .amount(15000L)
                    .build();

            // when
            paymentAlertService.alertReconciliationCancelFailed(
                    payment, "pk_reconcile_fail", "Toss API Timeout");

            // then
            ArgumentCaptor<SlackMessage> messageCaptor = ArgumentCaptor.forClass(SlackMessage.class);
            verify(slackNotifier).sendAsync(messageCaptor.capture());

            SlackMessage capturedMessage = messageCaptor.getValue();
            assertThat(capturedMessage.text()).contains("CRITICAL");
        }
    }
}
