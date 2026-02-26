package com.ureca.snac.payment.scheduler;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.dto.response.TossPaymentInquiryResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.TossRetryableException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.payment.service.PaymentAlertService;
import com.ureca.snac.payment.service.PaymentInternalService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    private final PaymentGatewayAdapter paymentGatewayAdapter;
    private final PaymentReconciliationProcessor processor;
    private final PaymentInternalService paymentInternalService;
    private final PaymentAlertService paymentAlertService;
    private final MeterRegistry meterRegistry;
    private final int staleThresholdMinutes;
    private final int batchSize;

    public PaymentReconciliationScheduler(
            PaymentRepository paymentRepository,
            PaymentGatewayAdapter paymentGatewayAdapter,
            PaymentReconciliationProcessor processor,
            PaymentInternalService paymentInternalService,
            PaymentAlertService paymentAlertService,
            MeterRegistry meterRegistry,
            @Value("${reconciliation.scheduler.stale-threshold-minutes}") int staleThresholdMinutes,
            @Value("${reconciliation.scheduler.batch-size}") int batchSize
    ) {
        this.paymentRepository = paymentRepository;
        this.paymentGatewayAdapter = paymentGatewayAdapter;
        this.processor = processor;
        this.paymentInternalService = paymentInternalService;
        this.paymentAlertService = paymentAlertService;
        this.meterRegistry = meterRegistry;
        this.staleThresholdMinutes = staleThresholdMinutes;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${reconciliation.scheduler.cron}")
    @SchedulerLock(name = "paymentReconciliation", lockAtMostFor = "PT4M", lockAtLeastFor = "PT1M")
    public void reconcileStalePendingPayments() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleThresholdMinutes);

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
                reconcilePayment(payment);
            } catch (Exception e) {
                log.error("[대사] 결제 대사 처리 중 예외. paymentId: {}, error: {}",
                        payment.getId(), e.getMessage(), e);
            }
        }
    }

    private void reconcilePayment(Payment payment) {
        if (payment.getStatus() == PaymentStatus.CANCEL_REQUESTED) {
            reconcileCancelRequested(payment);
            return;
        }
        reconcilePending(payment);
    }

    private void reconcilePending(Payment payment) {
        String orderId = payment.getOrderId();

        TossPaymentInquiryResponse tossResponse;
        try {
            tossResponse = paymentGatewayAdapter.inquirePaymentByOrderId(orderId);
        } catch (TossRetryableException e) {
            log.warn("[대사] 토스 조회 일시적 오류, 다음 주기에 재시도. orderId: {}", orderId);
            return;
        } catch (ExternalApiException e) {
            log.info("[대사] 토스에 결제 기록 없음. 로컬 취소 진행. orderId: {}", orderId);
            processor.cancelPayment(payment.getId(), "대사: 토스 결제 기록 없음 (orderId: " + orderId + ")");
            return;
        }

        if (tossResponse.isDone()) {
            handleTossDonePayment(payment, tossResponse);
        } else if (tossResponse.isCanceledOrFailed()) {
            log.info("[대사] 토스에서 이미 취소/만료 상태. 로컬 취소 진행. orderId: {}", orderId);
            processor.cancelPayment(payment.getId(),
                    "대사: 토스 상태 " + tossResponse.status() + " (orderId: " + orderId + ")");
        } else {
            log.info("[대사] 토스 결제 진행 중 상태({}). 스킵. orderId: {}", tossResponse.status(), orderId);
        }
    }

    private void reconcileCancelRequested(Payment payment) {
        String paymentKey = payment.getPaymentKey();
        String orderId = payment.getOrderId();
        String cancelReason = "대사: CANCEL_REQUESTED 미완료 취소 복구 (orderId: " + orderId + ")";

        TossPaymentInquiryResponse tossResponse;
        try {
            tossResponse = paymentGatewayAdapter.inquirePaymentByOrderId(orderId);
        } catch (TossRetryableException e) {
            log.warn("[대사] 토스 조회 일시적 오류, 다음 주기에 재시도. orderId: {}", orderId);
            return;
        } catch (ExternalApiException e) {
            log.info("[대사] 토스에 결제 기록 없음. 취소 완료 처리 진행. orderId: {}", orderId);
            paymentInternalService.completeCancellationForReconciliation(payment.getId(), cancelReason);
            return;
        }

        if (tossResponse.isDone()) {
            // 토스에서 아직 결제 상태 → 취소 API 호출 필요
            try {
                paymentGatewayAdapter.cancelPayment(paymentKey, cancelReason);
            } catch (TossRetryableException e) {
                log.warn("[대사] 토스 취소 일시적 오류, 다음 주기에 재시도. paymentKey: {}", paymentKey);
                return;
            } catch (ExternalApiException e) {
                log.info("[대사] 토스 취소 API 예외 (이미 취소 가능). paymentKey: {}, error: {}",
                        paymentKey, e.getMessage());
            }
        }
        // 토스 CANCELED/DONE(취소 성공) 모두 → 로컬 취소 완료 처리
        try {
            paymentInternalService.completeCancellationForReconciliation(payment.getId(), cancelReason);
            paymentAlertService.alertReconciliationAutoCanceled(payment, paymentKey);
        } catch (Exception e) {
            log.error("[대사] CANCEL_REQUESTED 취소 완료 실패! paymentId: {}, error: {}",
                    payment.getId(), e.getMessage(), e);
            paymentAlertService.alertReconciliationCancelFailed(payment, paymentKey, e.getMessage());
        }
    }

    private void handleTossDonePayment(Payment payment, TossPaymentInquiryResponse tossResponse) {
        String paymentKey = tossResponse.paymentKey();
        String cancelReason = "대사: JVM 크래시 후 미반영 결제 자동 환불 (orderId: " + payment.getOrderId() + ")";

        try {
            paymentGatewayAdapter.cancelPayment(paymentKey, cancelReason);
        } catch (TossRetryableException e) {
            log.warn("[대사] 토스 취소 일시적 오류, 다음 주기에 재시도. paymentKey: {}", paymentKey);
            return;
        } catch (ExternalApiException e) {
            // ALREADY_CANCELED_PAYMENT 등 — 토스 측 이미 취소됨, 로컬만 취소 진행
            log.info("[대사] 토스 취소 API 예외 (이미 취소 가능). 로컬 취소 진행. paymentKey: {}, error: {}",
                    paymentKey, e.getMessage());
        }

        try {
            processor.cancelPayment(payment.getId(), cancelReason);
            paymentAlertService.alertReconciliationAutoCanceled(payment, paymentKey);
        } catch (Exception e) {
            log.error("[대사] 로컬 취소 실패! paymentId: {}, error: {}",
                    payment.getId(), e.getMessage(), e);
            paymentAlertService.alertReconciliationCancelFailed(payment, paymentKey, e.getMessage());
        }
    }
}
