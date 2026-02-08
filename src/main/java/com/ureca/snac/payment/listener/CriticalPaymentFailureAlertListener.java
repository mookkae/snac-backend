package com.ureca.snac.payment.listener;

import com.ureca.snac.config.AsyncConfig;
import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import com.ureca.snac.payment.event.alert.CriticalPaymentFailureEvent;
import com.ureca.snac.payment.service.PaymentAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 관련 치명적 실패 이벤트 통합 리스너
 * <p>
 * 비동기로 Slack 알림 발송 (메인 트랜잭션에 영향 없음)
 * AFTER_COMPLETION: commit/rollback 무관하게 실행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CriticalPaymentFailureAlertListener {

    private final PaymentAlertService paymentAlertService;

    @Async(AsyncConfig.NOTIFICATION_EXECUTOR_NAME)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMPLETION)
    public void handleCriticalFailure(CriticalPaymentFailureEvent event) {
        try {
            if (event instanceof AutoCancelFailureEvent e) {
                paymentAlertService.alertAutoCancelFailure(e);
            } else if (event instanceof CompensationFailureEvent e) {
                paymentAlertService.alertCompensationFailure(e);
            }
        } catch (Exception e) {
            log.error("[CRITICAL ALERT FAILURE] 알림 발송 실패. paymentId: {}", event.paymentId(), e);
        }
    }
}
