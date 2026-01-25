package com.ureca.snac.payment.service;

import com.ureca.snac.asset.service.AssetHistoryService;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [신규 컴포넌트]
 * 결제와 관련된 단일 책임 내부 DB 상태 변경
 * 내부적인 헬퍼 역할
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentInternalService {

    private final PaymentRepository paymentRepository;
    private final WalletService walletService;
    private final AssetHistoryService assetHistoryService;
    private final ApplicationEventPublisher eventPublisher;
    private final MemberRepository memberRepository;

    /**
     * 결제 취소에 따른 내부 DB 상태 변경 책임
     * 토스페이먼츠 결제 취소 성공 후 호출
     *
     * @param payment        상태 변경할 Payment 엔티티
     * @param cancelResponse 우리서비스의 응답 DTO
     */
    @Transactional
    public void processCancellationInDB(Payment payment, Member member, PaymentCancelResponse cancelResponse) {
        log.info("[내부 처리] 결제 취소 DB 상태 변경 시작 paymentId : {}", payment.getId());

        Payment managedPayment = paymentRepository.save(payment);
        log.info("[내부 처리] 준영속 상태의 Payment 객체를 영속성 전환");

        // 관리 상태가 된 객체 변경
        managedPayment.cancel(cancelResponse.reason());
        log.info("[내부 처리] Payment 엔티티 상태 CANCELED 상태");

        // 머니 잔액을 회수
        Long balanceAfter = walletService.withdrawMoney(member.getId(),
                managedPayment.getAmount());
        log.info("[내부 처리] 지갑 머니 회수(출금) 완료 회원 ID : {}, 최종 잔액 : {}",
                member.getId(), balanceAfter);

        // 동기 직접 기록
        assetHistoryService.recordMoneyRechargeCancel(
                member.getId(), managedPayment.getId(), managedPayment.getAmount(), balanceAfter);
        log.info("[내부 처리] 자산 변동 기록 직접 저장 완료. 회원 ID : {}", member.getId());
    }

    /**
     * 결제 취소 DB 처리 실패 시 보상 처리
     * Payment 상태를 CANCELED로 변경
     * Outbox 이벤트 발행 (Wallet 환불 + AssetHistory 기록은 비동기 처리)
     */
    @Transactional
    public void compensateCancellationFailure(Payment payment, Member member,
                                              PaymentCancelResponse cancelResponse,
                                              Exception originalError) {
        try {
            log.warn("[결제 취소 보상] Payment 상태 복구 및 보상 이벤트 발행 시도. paymentId: {}",
                    payment.getId());

            // Payment 상태 CANCELED로 변경
            Payment freshPayment = paymentRepository.findById(payment.getId())
                    .orElse(payment);
            freshPayment.cancel(cancelResponse.reason() + " [보상 처리]");
            paymentRepository.save(freshPayment);

            log.info("[결제 취소 보상] Payment 상태 CANCELED로 복구 완료. paymentId: {}",
                    payment.getId());

            // Outbox 이벤트 발행 (Wallet 환불은 비동기로 처리)
            eventPublisher.publishEvent(new PaymentCancelCompensationEvent(
                    payment.getId(),
                    member.getId(),
                    payment.getAmount(),
                    cancelResponse.reason(),
                    cancelResponse.canceledAt()
            ));

            log.info("[결제 취소 보상] 보상 이벤트 발행 완료. paymentId: {}, memberId: {}",
                    payment.getId(), member.getId());

        } catch (Exception compensationError) {
            // 보상 처리마저 실패 - 최악의 상황
            log.error("[결제 취소 보상 실패] Payment 상태 복구 및 이벤트 발행 실패. " +
                            "반드시 수동 복구 필요! paymentId: {}, memberId: {}, amount: {}, " +
                            "원본 오류: {}, 보상 오류: {}",
                    payment.getId(),
                    member.getId(),
                    payment.getAmount(),
                    originalError.getMessage(),
                    compensationError.getMessage(),
                    compensationError);
        }
    }

    /**
     * 결제 취소 보상 이벤트 처리
     * 토스 취소 성공 후 DB 처리 실패 시 Wallet 환불 및 AssetHistory 기록
     * Listener에서 호출하여 self-invocation 문제 해결
     */
    @Transactional
    public void processCompensation(PaymentCancelCompensationEvent event) {

        Member member = memberRepository.findById(event.memberId())
                .orElseThrow(MemberNotFoundException::new);

        // 환불 금액 출금 (이미 토스에서 취소됨 -> 시스템 잔액 차감 필요)
        Long balanceAfter = walletService.withdrawMoney(event.memberId(), event.amount());

        log.info("[결제 취소 보상] Wallet 출금 완료. memberId: {}, amount: {}, balanceAfter: {}",
                event.memberId(), event.amount(), balanceAfter);

        // 기록 저장
        assetHistoryService.recordMoneyRechargeCancel(
                member.getId(), event.paymentId(), event.amount(), balanceAfter);

        log.info("[결제 취소 보상] AssetHistory 기록 완료. paymentId: {}", event.paymentId());
    }
}
