package com.ureca.snac.money.service;

import com.ureca.snac.common.exception.InternalServerException;
import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.money.dto.MoneyRechargeSuccessResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final MemberRepository memberRepository;
    private final PaymentService paymentService;
    private final PaymentGatewayAdapter paymentGatewayAdapter;
    // 머니 입금 DB 처리 담당 서비스
    private final MoneyDepositor moneyDepositor;
    // 이벤트 발행
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public MoneyRechargePreparedResponse prepareRecharge(MoneyRechargeRequest request, String email) {

        log.info("[머니 충전 준비] 시작. 회원 : {}, 요청 금액 : {}", email, request.amount());

        Member member = memberRepository.findByEmail(email)
                .orElseThrow(MemberNotFoundException::new);

        Payment payment = paymentService.preparePayment(member, request.amount());

        log.info("[머니 충전 준비] Payment 생성 완료 주문번호 : {}", payment.getOrderId());

        // 응답 Dto 생성 및 반환
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
            String paymentKey, String orderId, Long amount, String email) {

        log.info("[머니 충전 처리] 시작. 주문 번호 : {}, 요청 금액 : {}", orderId, amount);

        Member member = memberRepository.findByEmail(email)
                .orElseThrow(MemberNotFoundException::new);

        // 1. 트랜잭션 내에서 Payment 검증
        Payment payment = paymentService.findAndValidateForConfirmation(orderId, amount, member);

        // 2. 트랜잭션 밖에서 Toss 승인 요청
        // 예외 전파 시 Toss가 승인하지 않았으므로 돈이 빠지지 않아 취소 불필요.
        TossConfirmResponse tossConfirmResponse =
                paymentGatewayAdapter.confirmPayment(paymentKey, orderId, amount);

        // 3. 여기 도달 = Toss 승인 완료 (돈 빠짐!)
        // 이제부터 실패하면 반드시 Auto-Cancel 필요
        try {
            // DB 저장 시도
            moneyDepositor.deposit(payment, member, tossConfirmResponse);
        } catch (Exception e) {
            // Toss 성공 -> 우리 DB 실패 Auto-Cancel
            log.error("[결제 누락 위험] Toss 승인 완료 but DB 실패. Auto-Cancel 시도. orderId: {}", orderId);

            autoCancelAfterConfirmSuccess(payment, tossConfirmResponse.paymentKey(), e);
            throw new InternalServerException(PAYMENT_INTERNAL_ERROR);
        }
        log.info("[머니 충전 처리 완료] 모든 프로세스 종료");

        return MoneyRechargeSuccessResponse.of(orderId, paymentKey, amount);
    }

    // Toss 승인 성공 후 Auto-Cancel 실패 시 이벤트 발행
    private void autoCancelAfterConfirmSuccess(Payment payment, String paymentKey, Exception cause) {

        try {
            paymentGatewayAdapter.cancelPayment(paymentKey, "Auto-cancel : DB 처리 실패");

            paymentService.markAsCanceled(payment.getId(), "internal error by Auto-cancel");

            log.info("[Auto-Cancel 성공] paymentKey: {}", paymentKey);
        } catch (Exception cancelException) {
            // Auto-Cancel 실패 -> 이벤트 발행
            log.error("[Auto-Cancel 실패] 수동 복구 필요. paymentKey: {}, orderId: {}, amount: {}, 원인: {}, 취소실패원인: {}",
                    paymentKey, payment.getOrderId(), payment.getAmount(), cause.getMessage(), cancelException.getMessage());

            eventPublisher.publishEvent(new AutoCancelFailureEvent(
                    payment.getId(),
                    payment.getMember().getId(),
                    payment.getAmount(),
                    payment.getOrderId(),
                    paymentKey,
                    cause.getMessage(),
                    cancelException.getMessage()
            ));
        }
    }
}