package com.ureca.snac.payment.scheduler;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.dto.PaymentInquiryResult;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.AlreadyCanceledPaymentException;
import com.ureca.snac.payment.port.out.exception.GatewayTransientException;
import com.ureca.snac.payment.port.out.exception.GatewayNotCancelableException;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.service.PaymentAlertNotifier;
import com.ureca.snac.payment.service.PaymentInternalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 결제 대사 오케스트레이터
 * <p>
 * 스케줄러로부터 위임받아 개별 결제의 상태를 토스와 대조하고 정합성을 복구한다.
 * PENDING, CANCEL_REQUESTED 두 경로의 복구 전략을 각각 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciliationOrchestrator {

    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentInternalService paymentInternalService;
    private final PaymentAlertNotifier paymentAlertNotifier;

    public void reconcile(Payment payment) {
        if (payment.getStatus() == PaymentStatus.CANCEL_REQUESTED) {
            reconcileCancelRequested(payment);
            return;
        }
        reconcilePending(payment);
    }

    private void reconcilePending(Payment payment) {
        String orderId = payment.getOrderId();

        PaymentInquiryResult inquiryResult;
        try {
            inquiryResult = paymentGatewayPort.inquirePaymentByOrderId(orderId);
        } catch (GatewayTransientException e) {
            log.warn("[대사] 토스 조회 일시적 오류, 다음 주기에 재시도. orderId: {}", orderId);
            return;
        } catch (PaymentNotFoundException e) {
            // 토스에 결제 기록 자체가 없음 — PENDING은 출금 전이므로 안전하게 로컬 취소
            log.info("[대사] 토스에 결제 기록 없음. 로컬 취소 진행. orderId: {}", orderId);
            paymentInternalService.cancelPendingPayment(payment.getId(),
                    "대사: 토스 결제 기록 없음 (orderId: " + orderId + ")");
            return;
        } catch (ExternalApiException e) {
            // 분류 불가 오류 — 토스 실제 상태 불확실, 다음 주기 재시도
            log.warn("[대사] 토스 조회 분류 불가 오류, 다음 주기 재시도. orderId: {}, error: {}",
                    orderId, e.getMessage());
            return;
        }

        if (inquiryResult.isDone()) {
            handleTossDonePayment(payment, inquiryResult);
        } else if (inquiryResult.isCanceledOrFailed()) {
            log.info("[대사] 토스에서 이미 취소/만료 상태. 로컬 취소 진행. orderId: {}", orderId);
            paymentInternalService.cancelPendingPayment(payment.getId(),
                    "대사: 토스 상태 " + inquiryResult.status() + " (orderId: " + orderId + ")");
        } else {
            log.info("[대사] 토스 결제 진행 중 상태({}). 스킵. orderId: {}", inquiryResult.status(), orderId);
        }
    }

    private void reconcileCancelRequested(Payment payment) {
        String paymentKey = payment.getPaymentKey();
        String orderId = payment.getOrderId();
        String cancelReason = "대사: CANCEL_REQUESTED 미완료 취소 복구 (orderId: " + orderId + ")";

        PaymentInquiryResult inquiryResult;
        try {
            inquiryResult = paymentGatewayPort.inquirePaymentByOrderId(orderId);
        } catch (GatewayTransientException e) {
            log.warn("[대사] 토스 조회 일시적 오류, 다음 주기에 재시도. orderId: {}", orderId);
            return;
        } catch (ExternalApiException e) {
            // CANCEL_REQUESTED = 이미 Toss 승인 완료(SUCCESS)된 결제 → 분류 불가 오류는
            // Toss 실제 상태 불확실 → completeCancellation 호출 시 환불 없이 frozen 머니 소각 위험
            // Fail-Safe: frozen 유지, 다음 주기 재시도 + 알림
            log.error("[대사] 토스 조회 분류 불가 오류. CANCEL_REQUESTED frozen 유지. orderId: {}, error: {}",
                    orderId, e.getMessage());
            paymentAlertNotifier.alertReconciliationCancelFailed(
                    payment.getId(), payment.getOrderId(), payment.getAmount(), paymentKey, e.getMessage());
            return;
        }

        if (inquiryResult.isDone()) {
            // 토스에서 아직 결제 상태 → 취소 API 호출 필요
            try {
                paymentGatewayPort.cancelPayment(paymentKey, cancelReason);
            } catch (GatewayTransientException e) {
                log.warn("[대사] 토스 취소 일시적 오류, 다음 주기에 재시도. paymentKey: {}", paymentKey);
                return;
            } catch (AlreadyCanceledPaymentException e) {
                // Toss가 이미 취소 완료 (race condition) → fall-through, 로컬 취소 완료 처리
                log.info("[대사] 토스에서 이미 취소된 결제. 로컬 취소 완료 처리. paymentKey: {}", paymentKey);
            } catch (GatewayNotCancelableException e) {
                // 취소 불가 확정 → frozen 해제 + SUCCESS 복구
                log.warn("[대사] Toss 취소 거절 확정. frozen 해제 + SUCCESS 복구. paymentId: {}", payment.getId());
                paymentInternalService.handleCancellationRejected(payment.getId());
                paymentAlertNotifier.alertCancellationRejectedByGateway(
                        payment.getId(), payment.getOrderId(), payment.getAmount(), paymentKey, "스케줄러");
                return;
            } catch (ExternalApiException e) {
                // 분류 불가 오류 → Fail-Safe: frozen 유지, 다음 주기 재시도
                log.error("[대사] 토스 취소 API 분류 불가 오류. frozen 유지. paymentKey: {}, error: {}",
                        paymentKey, e.getMessage());
                paymentAlertNotifier.alertReconciliationCancelFailed(
                        payment.getId(), payment.getOrderId(), payment.getAmount(), paymentKey, e.getMessage());
                return;
            }
        } else if (inquiryResult.isCanceledOrFailed()) {
            // 토스에서 이미 취소/실패 상태 → fall-through, 로컬 취소 완료 처리
            log.info("[대사] 토스에서 이미 취소/실패 상태. 로컬 취소 완료 처리. orderId: {}", orderId);
        } else {
            // 비종료 상태 (READY, IN_PROGRESS 등) → 아직 처리 중, 다음 주기에 재확인
            log.warn("[대사] CANCEL_REQUESTED Toss 비종료 상태({}). 스킵. paymentId: {}",
                    inquiryResult.status(), payment.getId());
            return;
        }

        // 토스 CANCELED 또는 DONE(취소 성공) → 로컬 취소 완료 처리
        try {
            paymentInternalService.completeCancellationForReconciliation(payment.getId(), cancelReason);
            paymentAlertNotifier.alertReconciliationAutoCanceled(
                    payment.getId(), payment.getOrderId(), payment.getAmount(), paymentKey);
        } catch (Exception e) {
            log.error("[대사] CANCEL_REQUESTED 취소 완료 실패! paymentId: {}, error: {}",
                    payment.getId(), e.getMessage(), e);
            paymentAlertNotifier.alertReconciliationCancelFailed(
                    payment.getId(), payment.getOrderId(), payment.getAmount(), paymentKey, e.getMessage());
        }
    }

    private void handleTossDonePayment(Payment payment, PaymentInquiryResult inquiryResult) {
        String paymentKey = inquiryResult.paymentKey();
        String cancelReason = "대사: JVM 크래시 후 미반영 결제 자동 환불 (orderId: " + payment.getOrderId() + ")";

        try {
            paymentGatewayPort.cancelPayment(paymentKey, cancelReason);
        } catch (GatewayTransientException e) {
            log.warn("[대사] 토스 취소 일시적 오류, 다음 주기에 재시도. paymentKey: {}", paymentKey);
            return;
        } catch (AlreadyCanceledPaymentException e) {
            // Toss가 이미 취소 완료 → fall-through, 로컬 취소 진행
            log.info("[대사] 토스에서 이미 취소된 결제. 로컬 취소 진행. paymentKey: {}", paymentKey);
        } catch (GatewayNotCancelableException e) {
            // PENDING + 취소 불가 = 수동 개입 필요 (frozen 없으므로 wallet 영향 없음)
            log.error("[대사] PENDING 결제 Toss 취소 거절. 수동 복구 필요. paymentId: {}", payment.getId());
            paymentAlertNotifier.alertCancellationRejectedByGateway(
                    payment.getId(), payment.getOrderId(), payment.getAmount(), paymentKey, "대사-PENDING");
            return;
        } catch (ExternalApiException e) {
            // 분류 불가 오류 → Fail-Safe: 다음 주기 재시도
            log.error("[대사] 토스 취소 API 분류 불가 오류. paymentKey: {}, error: {}",
                    paymentKey, e.getMessage());
            paymentAlertNotifier.alertReconciliationCancelFailed(
                    payment.getId(), payment.getOrderId(), payment.getAmount(), paymentKey, e.getMessage());
            return;
        }

        try {
            paymentInternalService.cancelPendingPayment(payment.getId(), cancelReason);
            paymentAlertNotifier.alertReconciliationAutoCanceled(
                    payment.getId(), payment.getOrderId(), payment.getAmount(), paymentKey);
        } catch (Exception e) {
            log.error("[대사] 로컬 취소 실패! paymentId: {}, error: {}",
                    payment.getId(), e.getMessage(), e);
            paymentAlertNotifier.alertReconciliationCancelFailed(
                    payment.getId(), payment.getOrderId(), payment.getAmount(), paymentKey, e.getMessage());
        }
    }
}
