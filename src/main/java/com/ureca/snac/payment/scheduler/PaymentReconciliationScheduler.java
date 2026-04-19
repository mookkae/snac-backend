package com.ureca.snac.payment.scheduler;

import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 대사 스케줄러
 * <p>
 * JVM 크래시 등으로 토스에서는 승인됐지만 로컬 DB에 반영되지 않은
 * PENDING 결제를 감지하고 자동 취소 처리
 * <p>
 * 안전 전략: 사용자에게 실패 응답이 갔으므로 승인 완료 대신 자동 환불 처리
 */
@Slf4j
@Component
public class PaymentReconciliationScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentReconciliationOrchestrator orchestrator;
    private final int staleThresholdMinutes;
    private final int batchSize;
    private final Clock clock;

    public PaymentReconciliationScheduler(
            PaymentRepository paymentRepository,
            PaymentReconciliationOrchestrator orchestrator,
            @Value("${reconciliation.scheduler.stale-threshold-minutes}") int staleThresholdMinutes,
            @Value("${reconciliation.scheduler.batch-size}") int batchSize,
            Clock clock
    ) {
        this.paymentRepository = paymentRepository;
        this.orchestrator = orchestrator;
        this.staleThresholdMinutes = staleThresholdMinutes;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    @Scheduled(cron = "${reconciliation.scheduler.cron}")
    public void reconcileStalePayments() {
        LocalDateTime threshold = LocalDateTime.now(clock).minusMinutes(staleThresholdMinutes);

        List<Payment> stalePayments = paymentRepository.findStalePayments(
                List.of(PaymentStatus.PENDING, PaymentStatus.CANCEL_REQUESTED),
                threshold, PageRequest.of(0, batchSize));

        if (stalePayments.isEmpty()) {
            log.debug("[대사] 문제된 결제 없음");
            return;
        }

        log.info("[대사] 문제된 결제 {} 건 발견. 대사 시작", stalePayments.size());

        for (Payment payment : stalePayments) {
            try {
                orchestrator.reconcile(payment);
            } catch (Exception e) {
                log.error("[대사] 결제 대사 처리 중 예외. paymentId: {}, error: {}",
                        payment.getId(), e.getMessage(), e);
            }
        }
    }
}
