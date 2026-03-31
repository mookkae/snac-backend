package com.ureca.snac.payment.listener;

import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import com.ureca.snac.payment.service.PaymentAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 관련 치명적 실패 이벤트 통합 리스너
 * <p>
 * AFTER_COMPLETION: commit/rollback 무관하게 실행
 * 메시지 조립은 동기, 실제 HTTP 전송은 SlackNotifier.sendAsync()에서 비동기 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CriticalPaymentFailureAlertListener {

    private final PaymentAlertService paymentAlertService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    public void handleAutoCancelFailure(AutoCancelFailureEvent event) {
        try {
            paymentAlertService.alertAutoCancelFailure(event);
        } catch (Exception e) {
            log.error("[CRITICAL ALERT FAILURE] 알림 발송 실패. paymentId: {}", event.paymentId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    public void handleCompensationFailure(CompensationFailureEvent event) {
        try {
            paymentAlertService.alertCompensationFailure(event);
        } catch (Exception e) {
            log.error("[CRITICAL ALERT FAILURE] 알림 발송 실패. paymentId: {}", event.paymentId(), e);
        }
    }
}
