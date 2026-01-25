package com.ureca.snac.payment.service;

import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.dto.PaymentFailureRequest;
import com.ureca.snac.payment.entity.Payment;
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

        // Payment 객체에게 모든 취소관련 검증을 위임
        payment.validateForCancellation(member, currentUserBalance);
        log.info("[결제 취소] 검증 통과");

        // 외부 API 호출 트랜잭션 외부니까
        log.info("[결제 취소] 외부 TOSS API 호출 시작. paymentKey : {}", paymentKey);
        PaymentCancelResponse cancelResponse =
                paymentGatewayAdapter.cancelPayment(paymentKey, reason);
        log.info("[결제 취소] 외부 TOSS API 호출 성공");

        // 책임 위임 DB 상태 변경은 내부 서비스 계층에다가
        try {
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
    public void processPaymentFailure(PaymentFailureRequest request) {
        log.warn("[결제 실패 처리] 시작. 주문 ID : {}, 에러 코드 : {}",
                request.orderId(), request.errorCode());

        Payment payment = paymentRepository.findByOrderIdWithMemberForUpdate(request.orderId())
                .orElseThrow(PaymentNotFoundException::new);

        payment.reportFailureAsCancellation(request.errorCode(),
                request.errorMessage());

        // 디비 반영
        paymentRepository.save(payment);

        log.warn("[결제 실패 처리] 완료. 주문 ID : {} 상태를 CANCELED 로 변경",
                payment.getOrderId());
    }
}
