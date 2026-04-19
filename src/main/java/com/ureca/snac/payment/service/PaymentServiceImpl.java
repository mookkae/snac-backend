package com.ureca.snac.payment.service;

import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.port.out.dto.PaymentCancelResult;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.AlreadyUsedRechargeCannotCancelException;
import com.ureca.snac.payment.port.out.exception.GatewayTransientException;
import com.ureca.snac.payment.port.out.exception.GatewayNotCancelableException;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentInternalService paymentInternalService;
    private final PaymentAlertNotifier paymentAlertNotifier;
    private final MeterRegistry meterRegistry;
    private final Clock clock;


    @Override
    @Transactional
    public Payment preparePayment(Member member, Long amount) {
        Payment payment = Payment.prepare(member, amount);
        return paymentRepository.save(payment);
    }

    @Override
    public PaymentCancelResponse cancelPayment(String paymentKey, String reason, Long memberId) {
        String status = "fail";

        try {
            log.info("[결제 취소] 시작. 결제 ID : {}", paymentKey);

            Payment payment = paymentRepository.findByPaymentKeyWithMember(paymentKey)
                    .orElseThrow(PaymentNotFoundException::new);

            // 락 없는 사전 검증(fast-fail): 소유자·결제수단·기간을 빠르게 확인하여 불필요한 락 획득 방지
            // 소유자·결제수단은 생성 후 불변이므로 stale 스냅샷이어도 안전
            // 기간 정책은 prepareForCancellation에서 비관적 락 하에 재검증하여 최종 정합성 보장
            payment.validateForCancellation(memberId, clock);
            log.info("[결제 취소] 검증 통과");

            // 취소 준비: CANCEL_REQUESTED + 머니 동결 (단일 트랜잭션, 이중지불 취약 창 제거)
            // freezeMoney가 비관적 락 안에서 잔액 검증 → TOCTOU 제거
            try {
                paymentInternalService.prepareForCancellation(payment.getId());
            } catch (InsufficientBalanceException e) {
                throw new AlreadyUsedRechargeCannotCancelException();
            }
            log.info("[결제 취소] 취소 준비 완료 (CANCEL_REQUESTED + 머니 동결)");

            // 외부 API 호출 (트랜잭션 외부)
            log.info("[결제 취소] 외부 TOSS API 호출 시작. paymentKey : {}", paymentKey);
            PaymentCancelResult cancelResult;
            try {
                cancelResult = paymentGatewayPort.cancelPayment(paymentKey, reason);
            } catch (GatewayTransientException e) {
                // Unknown 결과 (5xx, timeout): Toss 실제 취소 여부 모를 때 frozen + CANCEL_REQUESTED 유지, 대사 스케줄러에 위임
                // 사용자에게는 "취소 처리 중" 응답 반환 — 예외 전파 시 "취소 실패" 오인 방지
                log.warn("[결제 취소] Toss Unknown 응답 (3회 재시도 소진). " +
                        "frozen + CANCEL_REQUESTED 유지. paymentId: {}", payment.getId());
                status = "processing";
                return PaymentCancelResponse.ofProcessing(paymentKey);
            } catch (GatewayNotCancelableException e) {
                // 취소 불가 확정 (NOT_CANCELABLE_PAYMENT) -> frozen 즉시 해제 + Payment SUCCESS 복구
                // Fail-Safe Closed 예외: 결과가 확정이므로 frozen 유지 불필요
                log.warn("[결제 취소] Toss 취소 거절 확정. frozen 해제 + SUCCESS 복구. paymentId: {}", payment.getId());

                paymentInternalService.handleCancellationRejected(payment.getId());
                paymentAlertNotifier.alertCancellationRejectedByGateway(
                        payment.getId(), payment.getOrderId(), payment.getAmount(), paymentKey, "직접 취소");
                throw e;
            } catch (ExternalApiException e) {
                // 분류되지 않은 외부 API 예외 = Unknown (네트워크 오류 등) -> Fail-Safe Closed: frozen 유지
                // revert 금지: Toss가 실제로 취소했을 가능성 배제 불가라서 frozen + CANCEL_REQUESTED 유지, 대사 스케줄러에 위임
                log.error("[결제 취소] 분류되지 않은 외부 API 예외. Fail-Safe: frozen + CANCEL_REQUESTED 유지. paymentId: {}", payment.getId(), e);
                status = "processing";
                return PaymentCancelResponse.ofProcessing(paymentKey);
            }
            log.info("[결제 취소] 외부 TOSS API 호출 성공");

            try {
                PaymentCancelResponse cancelResponse = PaymentCancelResponse.builder()
                        .paymentKey(cancelResult.paymentKey())
                        .canceledAmount(cancelResult.canceledAmount())
                        .canceledAt(cancelResult.canceledAt())
                        .reason(cancelResult.reason())
                        .processing(false)
                        .build();

                paymentInternalService.processCancellationInDB(payment.getId(), cancelResponse);
                log.info("[결제 취소] 내부 상태 변경 완료");
                status = "success";
            } catch (Exception e) {
                status = "compensation_triggered";
                log.error("[결제 취소 보상 필요] 토스 취소 성공 but DB 실패. " +
                                "수동 복구 필요! paymentKey: {}, memberId: {}, amount: {}, " +
                                "canceledAt: {}, reason: {}",
                        paymentKey,
                        memberId,
                        payment.getAmount(),
                        cancelResult.canceledAt(),
                        reason,
                        e);

                PaymentCancelResponse failedResponse = PaymentCancelResponse.builder()
                        .paymentKey(cancelResult.paymentKey())
                        .canceledAmount(cancelResult.canceledAmount())
                        .canceledAt(cancelResult.canceledAt())
                        .reason(cancelResult.reason())
                        .processing(false)
                        .build();
                paymentInternalService.compensateCancellationFailure(payment, memberId, failedResponse, e);

                throw e;
            }

            return PaymentCancelResponse.builder()
                    .paymentKey(cancelResult.paymentKey())
                    .canceledAmount(cancelResult.canceledAmount())
                    .canceledAt(cancelResult.canceledAt())
                    .reason(cancelResult.reason())
                    .processing(false)
                    .build();
        } finally {
            meterRegistry.counter("payment_cancel_total", "status", status).increment();
        }
    }

    @Override
    @Transactional
    public void markAsCanceled(Long paymentId, String reason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(PaymentNotFoundException::new);
        if (payment.getStatus() == PaymentStatus.CANCELED) {
            log.info("[markAsCanceled] 이미 취소된 결제. 멱등성 보장. paymentId: {}", paymentId);
            return;
        }
        payment.cancel(reason);
    }

    /**
     * 결제 확정 전 검증
     *
     * @param orderId  주문 ID
     * @param amount   결제 금액
     * @param memberId 결제 요청자 ID
     * @return 검증된 Payment 엔티티
     */
    @Override
    @Transactional
    public Payment findAndValidateForConfirmation(String orderId, Long amount, Long memberId) {
        log.info("[데이터 정합성 확인] 시작. 주문번호 : {}", orderId);

        Payment payment = paymentRepository.findByOrderIdWithMemberForUpdate(orderId)
                .orElseThrow(PaymentNotFoundException::new);

        payment.validateForConfirmation(memberId, amount);

        log.info("[데이터 정합성 확인] 모든 검증 통과. 주문번호 : {}", orderId);
        return payment;
    }
}
