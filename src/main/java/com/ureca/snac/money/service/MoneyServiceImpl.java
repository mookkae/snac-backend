package com.ureca.snac.money.service;

import com.ureca.snac.common.exception.InternalServerException;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.money.dto.MoneyRechargeSuccessResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.exception.AlreadyCanceledPaymentException;
import com.ureca.snac.payment.exception.PaymentAlreadySuccessException;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.payment.service.PaymentService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import static com.ureca.snac.common.BaseCode.PAYMENT_INTERNAL_ERROR;

/**
 * 머니 충전 서비스 Fail-Fast
 * Toss 승인 실패 -> 예외 전파 (출금 안됨, 취소 필요 없음)
 * Toss 승인 성공 + DB 실패 -> Auto-Cancel 시도 (출금 됨, 반드시 취소 필요)
 * Auto-Cancel 마저 실패 -> Slack 알림 (수동 복구 필요)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneyServiceImpl implements MoneyService {

    private final PaymentService paymentService;
    private final PaymentGatewayPort paymentGatewayPort;
    private final MoneyDepositorRetryFacade moneyDepositorRetryFacade;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    @Override
    public MoneyRechargePreparedResponse prepareRecharge(MoneyRechargeRequest request, Member member) {

        log.info("[머니 충전 준비] 시작. 회원 ID : {}, 요청 금액 : {}", member.getId(), request.amount());

        Payment payment = paymentService.preparePayment(member, request.amount());

        log.info("[머니 충전 준비] Payment 생성 완료 주문번호 : {}", payment.getOrderId());

        return MoneyRechargePreparedResponse.builder()
                .orderId(payment.getOrderId())
                .orderName("스낵 머니 " + request.amount() + "원 충전")
                .amount(request.amount())
                .customerName(member.getName())
                .customerEmail(member.getEmail())
                .build();
    }

    /**
     * 머니 충전 처리 Fail-Fast
     * <p>
     * 1. 트랜잭션 내에서 Payment 검증 (FOR UPDATE 락)
     * 2. 트랜잭션 밖에서 Toss 승인 요청 (DB 커넥션 점유 방지)
     * 3. DB 저장 (실패 시 Auto-Cancel)
     * 트랜잭션 검증은 findAndValidateForConfirmation()에 위임
     */
    @Override
    public MoneyRechargeSuccessResponse processRechargeSuccess(
            String paymentKey, String orderId, Long amount, Long memberId) {

        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "success";

        try {
            log.info("[머니 충전 처리] 시작. 주문 번호 : {}, 요청 금액 : {}", orderId, amount);

            // 1. 트랜잭션 내에서 Payment 검증
            Payment payment = paymentService.findAndValidateForConfirmation(orderId, amount, memberId);

            // 2. 트랜잭션 밖에서 Toss 승인 요청
            // 예외 전파 시 Toss가 승인하지 않았으므로 돈이 빠지지 않아 취소 불필요.
            PaymentConfirmResult confirmResult;
            try {
                confirmResult = paymentGatewayPort.confirmPayment(paymentKey, orderId, amount);
            } catch (PaymentAlreadySuccessException e) {
                log.warn("[머니 충전 처리] 토스 기결제 건 확인. 조회 API로 데이터 복구. orderId: {}", orderId);
                var inquiryResult = paymentGatewayPort.inquirePaymentByOrderId(orderId);
                
                if (!inquiryResult.isDone()) {
                    log.error("[머니 충전 처리] 토스 기결제 건 조회 실패 (상태 불일치). orderId: {}, status: {}", orderId, inquiryResult.status());
                    throw new InternalServerException(PAYMENT_INTERNAL_ERROR);
                }

                confirmResult = new PaymentConfirmResult(paymentKey, inquiryResult.method(), inquiryResult.approvedAt());
            }

            // 3. 여기 도달 = Toss 승인 완료 (돈 빠짐!)
            // 이제부터 실패하면 반드시 Auto-Cancel 필요
            try {
                // DB 저장 시도
                moneyDepositorRetryFacade.deposit(payment.getId(), memberId, confirmResult);
            } catch (PaymentAlreadySuccessException e) {
                // 정상적인 멱등성 보장 응답: 이미 처리된 결제, DB 장애 아님 → Auto-Cancel 불필요
                log.info("[머니 충전 처리] 이미 처리된 결제. 멱등성 보장 응답 반환. orderId: {}", orderId);
                return MoneyRechargeSuccessResponse.of(orderId, paymentKey, amount);
            } catch (Exception e) {
                // Toss 성공 -> 우리 DB 실패 Auto-Cancel
                log.error("[결제 누락 위험] Toss 승인 완료 but DB 실패. Auto-Cancel 시도. orderId: {}", orderId);

                autoCancelAfterConfirmSuccess(payment, confirmResult.paymentKey(), memberId, e);
                throw new InternalServerException(PAYMENT_INTERNAL_ERROR);
            }
            log.info("[머니 충전 처리 완료] 모든 프로세스 종료");

            return MoneyRechargeSuccessResponse.of(orderId, paymentKey, amount);
        } catch (Exception e) {
            status = "fail";
            throw e;
        } finally {
            sample.stop(Timer.builder("payment_approval_duration").register(meterRegistry));
            Counter.builder("payment_approval_total")
                    .tag("status", status)
                    .register(meterRegistry)
                    .increment();
        }
    }

    // Toss 승인 성공 후 Auto-Cancel 실패 시 이벤트 발행
    // payment는 detached 엔티티 — id/amount/orderId/paymentKey 스칼라 필드만 접근.
    private void autoCancelAfterConfirmSuccess(Payment payment, String paymentKey, Long memberId, Exception cause) {

        try {
            paymentGatewayPort.cancelPayment(paymentKey, "Auto-cancel : DB 처리 실패");

            paymentService.markAsCanceled(payment.getId(), "internal error by Auto-cancel");

            log.info("[Auto-Cancel 성공] paymentKey: {}", paymentKey);
        } catch (AlreadyCanceledPaymentException e) {
            // 대사 스케줄러가 먼저 Toss 취소 + 로컬 CANCELED 처리 완료한 경우
            // Toss와 로컬 DB 모두 정상 취소 상태 → 수동 복구 불필요, Critical Alert 생략
            log.info("[Auto-Cancel] 대사 스케줄러에 의해 이미 취소 처리됨. paymentKey: {}", paymentKey);
        } catch (Exception cancelException) {
            // Auto-Cancel 실패 -> 이벤트 발행
            log.error("[Auto-Cancel 실패] 수동 복구 필요. paymentKey: {}, orderId: {}, amount: {}, 원인: {}, 취소실패원인: {}",
                    paymentKey, payment.getOrderId(), payment.getAmount(), cause.getMessage(), cancelException.getMessage());

            eventPublisher.publishEvent(new AutoCancelFailureEvent(
                    payment.getId(),
                    memberId,
                    payment.getAmount(),
                    payment.getOrderId(),
                    paymentKey,
                    cause.getMessage(),
                    cancelException.getMessage()
            ));
        }
    }
}