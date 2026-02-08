package com.ureca.snac.payment.service;

import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.exception.AlreadyUsedRechargeCannotCancelException;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final MemberRepository memberRepository;
    private final PaymentRepository paymentRepository;

    // 외부 통신은 어댑터를 통해 수행
    private final PaymentGatewayAdapter paymentGatewayAdapter;
    // 내부 하위 서비스 레이어 (DB 상태 변경 + 보상 처리)
    private final PaymentInternalService paymentInternalService;
    // 잔액검증만 쓸꺼
    private final WalletService walletService;


    @Override
    @Transactional
    public Payment preparePayment(Member member, Long amount) {
        // Payment 객체 생성하고
        Payment payment = Payment.prepare(member, amount);
        // 디비에 저장
        return paymentRepository.save(payment);
    }

    @Override
    public PaymentCancelResponse cancelPayment(String paymentKey, String reason, String email) {
        log.info("[결제 취소] 시작. 결제 ID : {}", paymentKey);

        Member member = memberRepository.findByEmail(email)
                .orElseThrow(MemberNotFoundException::new);

        Payment payment = paymentRepository.findByPaymentKeyWithMember(paymentKey)
                .orElseThrow(PaymentNotFoundException::new);

        long currentUserBalance = walletService.getMoneyBalance(member.getId());

        if (currentUserBalance < payment.getAmount()) {
            throw new AlreadyUsedRechargeCannotCancelException();
        }

        // Payment 객체에게 도메인 취소 검증을 위임
        payment.validateForCancellation(member);
        log.info("[결제 취소] 검증 통과");

        // 외부 API 호출 트랜잭션 외부니까
        log.info("[결제 취소] 외부 TOSS API 호출 시작. paymentKey : {}", paymentKey);
        PaymentCancelResponse cancelResponse =
                paymentGatewayAdapter.cancelPayment(paymentKey, reason);
        log.info("[결제 취소] 외부 TOSS API 호출 성공");

        try {
            // 책임 위임 DB 상태 변경은 내부 서비스 계층에다가
            paymentInternalService.processCancellationInDB(payment, member, cancelResponse);
            log.info("[결제 취소] 내부 상태 변경 완료");
        } catch (Exception e) {
            // 토스 취소 성공 + DB 실패 = 심각한 불일치 상태
            // 토스는 취소 완료 상태이므로 DB를 수동으로 맞춰야 함
            log.error("[결제 취소 보상 필요] 토스 취소 성공 but DB 실패. " +
                            "수동 복구 필요! paymentKey: {}, memberId: {}, amount: {}, " +
                            "canceledAt: {}, reason: {}",
                    paymentKey,
                    member.getId(),
                    payment.getAmount(),
                    cancelResponse.canceledAt(),
                    reason,
                    e);

            // DB 상태 복구 시도 (Payment 상태 CANCELED + Outbox 이벤트 발행)
            paymentInternalService.compensateCancellationFailure(payment, member, cancelResponse, e);

            // 예외를 다시 던져 사용자에게 오류 알림 (하지만 토스 취소는 이미 완료됨)
            throw e;
        }

        return cancelResponse;
    }

    @Override
    @Transactional
    public void markAsCanceled(Long paymentId, String reason) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(PaymentNotFoundException::new);
        payment.cancel(reason);
    }

    /**
     * 결제 확정 전 검증
     *
     * @param orderId 주문 ID
     * @param amount  결제 금액
     * @param member  결제 요청자
     * @return 검증된 Payment 엔티티
     */
    @Override
    @Transactional
    public Payment findAndValidateForConfirmation(String orderId, Long amount, Member member) {
        log.info("[데이터 정합성 확인] 시작. 주문번호 : {}", orderId);

        Payment payment = paymentRepository.findByOrderIdWithMemberForUpdate(orderId)
                .orElseThrow(PaymentNotFoundException::new);

        payment.validateForConfirmation(member, amount);

        log.info("[데이터 정합성 확인] 모든 검증 통과. 주문번호 : {}", orderId);
        return payment;
    }
}
