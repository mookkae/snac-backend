package com.ureca.snac.payment.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.wallet.service.WalletService;
import com.ureca.snac.common.metric.TransactionAwareMetricRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * 결제 취소 관련 DB 상태 변경을 담당하는 내부 서비스
 * <p>
 * 일반 취소 흐름(prepareForCancellation → processCancellationInDB),
 * 대사 스케줄러 복구 흐름(cancelPendingPayment, completeCancellationForReconciliation),
 * 보상 흐름(compensateCancellationFailure → processCompensation) 세 경로의
 * 트랜잭션 경계를 단일 지점에서 관리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentInternalService {

    private final PaymentRepository paymentRepository;
    private final WalletService walletService;
    private final AssetRecorder assetRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionAwareMetricRecorder metricRecorder;
    private final Clock clock;

    /**
     * 취소 준비: Payment 상태 변경 + Wallet 동결을 단일 트랜잭션으로 처리
     * 동결된 금액은 Toss 결과 확정 전까지 소비 불가 -> 이중지불 취약 창 제거
     */
    @Transactional
    public void prepareForCancellation(Long paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(PaymentNotFoundException::new);

        payment.validateCancellationPeriodNotExpired(clock);
        payment.requestCancellation();

        walletService.freezeMoney(payment.getMember().getId(), payment.getAmount());

        log.info("[취소 준비] CANCEL_REQUESTED + 머니 동결 완료. paymentId: {}", paymentId);
    }

    /**
     * 토스페이먼츠 결제 취소 성공 후 내부 DB 상태 반영
     * finalizeCancellation에 위임하여 취소 완결 로직을 단일 지점으로 유지
     */
    @Transactional
    public void processCancellationInDB(Long paymentId, PaymentCancelResponse cancelResponse) {
        log.info("[내부 처리] 결제 취소 DB 상태 변경 시작. paymentId: {}", paymentId);
        finalizeCancellation(paymentId, cancelResponse.reason());
    }

    /**
     * 결제 취소 완결 — canonical 구현
     * Payment CANCELED + 동결 머니 소각 + 자산 변동 기록을 원자적으로 처리
     * 대사 스케줄러(completeCancellationForReconciliation)와
     * 보상 리스너(processCompensation) 두 경로에서 공유
     *
     * <p>멱등성: Payment가 이미 CANCELED면 조기 반환 (대사/보상 중복 실행 안전)
     */
    private void finalizeCancellation(Long paymentId, String reason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(PaymentNotFoundException::new);

        if (payment.getStatus() == PaymentStatus.CANCELED) {
            log.warn("[취소 완결] 이미 취소된 결제. 중복 처리 방지. paymentId: {}", paymentId);
            return;
        }

        payment.cancel(reason);
        log.info("[취소 완결] Payment CANCELED. paymentId: {}", paymentId);

        Member member = payment.getMember();
        Long balanceAfter = walletService.deductFrozenMoney(member.getId(), payment.getAmount());
        log.info("[취소 완결] 동결 머니 소각 완료. memberId: {}, balanceAfter: {}", member.getId(), balanceAfter);

        assetRecorder.recordMoneyRechargeCancel(member.getId(), paymentId, payment.getAmount(), balanceAfter);
        log.info("[취소 완결] 자산 변동 기록 완료. paymentId: {}", paymentId);
    }

    /**
     * CANCEL_REQUESTED 결제 취소 완결 — 대사 스케줄러 전용
     * frozen 머니 소각 + 자산 기록까지 원자적으로 처리
     */
    @Transactional
    public void completeCancellationForReconciliation(Long paymentId, String cancelReason) {
        finalizeCancellation(paymentId, cancelReason);
    }

    /**
     * PENDING 결제 로컬 취소 (대사 전용)
     * wallet 조작 없음 — PENDING은 frozen 상태가 아님
     *
     * @return true: 취소 처리됨, false: 이미 처리된 건 (no-op)
     */
    @Transactional
    public boolean cancelPendingPayment(Long paymentId, String cancelReason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(PaymentNotFoundException::new);

        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("[대사] 이미 처리된 결제 건 스킵. paymentId: {}, status: {}", paymentId, payment.getStatus());
            return false;
        }

        payment.cancel(cancelReason);
        metricRecorder.increment("payment_reconciliation_resolved_total");
        log.info("[대사] PENDING 결제 로컬 취소 완료. paymentId: {}, reason: {}", paymentId, cancelReason);
        return true;
    }

    /**
     * Toss NOT_CANCELABLE_PAYMENT 확정 시 취소 거절 처리
     * frozen 해동 + Payment CANCEL_REQUESTED -> SUCCESS 복구
     */
    @Transactional
    public void handleCancellationRejected(Long paymentId) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(PaymentNotFoundException::new);

        payment.revertCancellation();
        walletService.unfreezeMoney(payment.getMember().getId(), payment.getAmount());

        log.info("[취소 거절 복구] Payment SUCCESS 복구 + frozen 해제 완료. paymentId: {}", paymentId);
    }

    /**
     * 결제 취소 DB 처리 실패 시 보상 처리
     *
     * <p>REQUIRES_NEW: Outbox 커밋은 outer 트랜잭션 롤백과 반드시 독립되어야 한다.
     * 현재 호출 경로(cancelPayment)에는 outer 트랜잭션이 없어 REQUIRED와 동일하게 동작하지만,
     * 향후 트랜잭션 컨텍스트에서 호출되는 경우를 대비한 계약 선언이다.
     *
     * <p>Payment는 CANCEL_REQUESTED로 유지 — 대사 스케줄러 시야에서 살아있음.
     * Outbox 이벤트만 발행 → 리스너가 취소 완결(Payment + Wallet + AssetHistory)을 원자적으로 수행.
     * Outbox 전달 영구 실패(DLQ) 시에도 대사 스케줄러가 자동 복구 — 수동 개입 불필요.
     *
     * <p>{@code payment}는 detached 엔티티다. id/amount/orderId/paymentKey 스칼라 필드만 접근하므로
     * LazyInitializationException 위험 없다.
     * {@code memberId}는 {@code payment.getMember()}를 통한 lazy 접근을 피하기 위해 분리하여 전달한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensateCancellationFailure(Payment payment, Long memberId,
                                              PaymentCancelResponse cancelResponse, Exception originalError) {
        metricRecorder.increment("payment_compensation_triggered_total");

        try {
            log.warn("[결제 취소 보상] 보상 이벤트 발행 시도. paymentId: {}", payment.getId());

            eventPublisher.publishEvent(new PaymentCancelCompensationEvent(
                    payment.getId(),
                    memberId,
                    payment.getAmount(),
                    cancelResponse.reason(),
                    cancelResponse.canceledAt()
            ));

            log.info("[결제 취소 보상] 보상 이벤트 발행 완료. paymentId: {}, memberId: {}",
                    payment.getId(), memberId);

        } catch (Exception compensationError) {
            // Outbox 저장마저 실패 -> Payment CANCEL_REQUESTED 유지 -> 대사 스케줄러가 복구
            log.error("[결제 취소 보상 실패] 이벤트 발행 실패. " +
                            "paymentId: {}, memberId: {}, amount: {}, " +
                            "원본 오류: {}, 보상 오류: {}",
                    payment.getId(),
                    memberId,
                    payment.getAmount(),
                    originalError.getMessage(),
                    compensationError.getMessage(),
                    compensationError);

            // @EventListener는 트랜잭션 바인딩 없이 즉시 동기 실행 -> 롤백 상태에서도 신뢰성 확보
            eventPublisher.publishEvent(new CompensationFailureEvent(
                    payment.getId(),
                    memberId,
                    payment.getAmount(),
                    payment.getOrderId(),
                    payment.getPaymentKey(),
                    cancelResponse.reason(),
                    cancelResponse.canceledAt(),
                    originalError.getMessage(),
                    compensationError.getMessage()
            ));
        }
    }

    /**
     * 결제 취소 보상 이벤트 처리
     * Listener에서 호출하여 self-invocation 문제 해결
     */
    @Transactional
    public void processCompensation(PaymentCancelCompensationEvent event) {
        finalizeCancellation(event.paymentId(), event.reason());
        log.info("[결제 취소 보상] 보상 처리 완료. paymentId: {}", event.paymentId());
    }
}
