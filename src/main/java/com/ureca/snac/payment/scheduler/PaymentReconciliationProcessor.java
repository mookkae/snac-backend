package com.ureca.snac.payment.scheduler;

import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 대사 스케줄러의 트랜잭션 처리 헬퍼
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciliationProcessor {

    private final PaymentRepository paymentRepository;

    /**
     * 결제를 취소 상태로 변경 (멱등성 보장)
     *
     * @param paymentId    결제 ID
     * @param cancelReason 취소 사유
     * @return true: 취소 처리됨, false: 이미 처리된 건 (no-op)
     */
    @Transactional
    public boolean cancelPayment(Long paymentId, String cancelReason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(PaymentNotFoundException::new);

        if (payment.getStatus() != PaymentStatus.PENDING) {
            log.info("[대사] 이미 처리된 결제 건 스킵. paymentId: {}, status: {}", paymentId, payment.getStatus());
            return false;
        }

        payment.cancel(cancelReason);
        log.info("[대사] 결제 로컬 취소 완료. paymentId: {}, reason: {}", paymentId, cancelReason);
        return true;
    }
}
