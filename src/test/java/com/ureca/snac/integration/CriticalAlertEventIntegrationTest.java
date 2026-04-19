package com.ureca.snac.integration;

import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CriticalPaymentFailureAlertListener 이벤트 수신 통합 테스트 (실제 슬랙 발송)
 */
@DisplayName("치명적 결제 알림 이벤트 수신 통합 테스트")
class CriticalAlertEventIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final AutoCancelFailureEvent testEvent = new AutoCancelFailureEvent(
            1L, 1L, 10000L, "test-order", "test-key", "DB 실패 (통합테스트)", "취소 실패 (통합테스트)"
    );

    @Nested
    @DisplayName("트랜잭션 내에서 이벤트 발행")
    class WithTransactionContext {

        @Test
        @DisplayName("성공 : 트랜잭션 내 발행 시 리스너가 이벤트를 수신하여 실제 슬랙을 발송한다")
        void shouldReceiveEvent_WhenPublishedInsideTransaction() throws InterruptedException {
            TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

            txTemplate.executeWithoutResult(status -> {
                eventPublisher.publishEvent(testEvent);
            });

            // 비동기 슬랙 전송 대기
            Thread.sleep(3000);
        }
    }

    @Nested
    @DisplayName("트랜잭션 없이 이벤트 발행")
    class WithoutTransactionContext {

        @Test
        @DisplayName("성공 : @EventListener로 전환 후 트랜잭션 없이 발행해도 실제 슬랙을 발송한다")
        void shouldReceiveEvent_WhenPublishedOutsideTransaction() throws InterruptedException {
            eventPublisher.publishEvent(testEvent);

            // 비동기 슬랙 전송 대기
            Thread.sleep(3000);
        }
    }
}
