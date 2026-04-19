package com.ureca.snac.payment.service;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.AlreadyUsedRechargeCannotCancelException;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.dto.PaymentCancelResult;
import com.ureca.snac.payment.port.out.exception.GatewayNotCancelableException;
import com.ureca.snac.payment.port.out.exception.GatewayTransientException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.wallet.service.WalletService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentInternalService paymentInternalService;
    private final WalletService walletService;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public Payment preparePayment(Member member, Long amount) {
        Payment payment = Payment.prepare(member, amount);
        return paymentRepository.save(payment);
    }

    @Override
    public PaymentCancelResponse cancelPayment(String paymentKey, String reason, String email) {
        String status = "fail";

        try {
            log.info("[결제 취소] 시작. 결제 ID : {}", paymentKey);

            Member member = memberRepository.findByEmail(email)
                    .orElseThrow(MemberNotFoundException::new);

            Payment payment = paymentRepository.findByPaymentKeyWithMember(paymentKey)
                    .orElseThrow(PaymentNotFoundException::new);

            long currentUserBalance = walletService.getMoneyBalance(member.getId());
            if (currentUserBalance < payment.getAmount()) {
                throw new AlreadyUsedRechargeCannotCancelException();
            }

            payment.validateForCancellation(member);
            log.info("[결제 취소] 검증 통과");

            paymentInternalService.markAsCancelRequested(payment.getId());
            log.info("[결제 취소] 취소 의도 DB 기록 완료");

            log.info("[결제 취소] 외부 TOSS API 호출 시작. paymentKey : {}", paymentKey);
            PaymentCancelResult cancelResult;
            try {
                cancelResult = paymentGatewayPort.cancelPayment(paymentKey, reason);
            } catch (GatewayTransientException | GatewayNotCancelableException e) {
                status = "fail";
                throw e;
            }
            log.info("[결제 취소] 외부 TOSS API 호출 성공");

            try {
                PaymentCancelResponse cancelResponse = PaymentCancelResponse.builder()
                        .paymentKey(cancelResult.paymentKey())
                        .canceledAmount(cancelResult.canceledAmount())
                        .canceledAt(cancelResult.canceledAt())
                        .reason(cancelResult.reason())
                        .build();

                paymentInternalService.processCancellationInDB(payment, member, cancelResponse);
                log.info("[결제 취소] 내부 상태 변경 완료");
                status = "success";
                return cancelResponse;
            } catch (Exception e) {
                status = "compensated";
                log.error("[결제 취소 보상 필요] 토스 취소 성공 but DB 실패. " +
                                "수동 복구 필요! paymentKey: {}, memberId: {}, amount: {}, reason: {}",
                        paymentKey, member.getId(), payment.getAmount(), reason, e);

                PaymentCancelResponse failedResponse = PaymentCancelResponse.builder()
                        .paymentKey(cancelResult.paymentKey())
                        .canceledAmount(cancelResult.canceledAmount())
                        .canceledAt(cancelResult.canceledAt())
                        .reason(cancelResult.reason())
                        .build();
                paymentInternalService.compensateCancellationFailure(payment, member, failedResponse, e);
                throw e;
            }
        } finally {
            Counter.builder("payment_cancel_total")
                    .tag("status", status)
                    .register(meterRegistry)
                    .increment();
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