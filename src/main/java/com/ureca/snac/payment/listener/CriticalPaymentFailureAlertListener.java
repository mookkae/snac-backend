package com.ureca.snac.payment.listener;

import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import com.ureca.snac.payment.service.PaymentAlertNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 결제 관련 치명적 실패 이벤트 통합 리스너
 * 메시지 조립은 동기, 실제 HTTP 전송은 SlackNotifier.sendAsync()에서 비동기 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CriticalPaymentFailureAlertListener {

    private final PaymentAlertNotifier paymentAlertNotifier;

    @EventListener
    public void handleAutoCancelFailure(AutoCancelFailureEvent event) {
        try {
            paymentAlertNotifier.alertAutoCancelFailure(event);
        } catch (Exception e) {
            log.error("[CRITICAL ALERT FAILURE] 알림 발송 실패. paymentId: {}", event.paymentId(), e);
        }
    }

    @EventListener
    public void handleCompensationFailure(CompensationFailureEvent event) {
        try {
            paymentAlertNotifier.alertCompensationFailure(event);
        } catch (Exception e) {
            log.error("[CRITICAL ALERT FAILURE] 보상 처리 실패 알림 발송 실패. paymentId: {}", event.paymentId(), e);
        }
    }
}
