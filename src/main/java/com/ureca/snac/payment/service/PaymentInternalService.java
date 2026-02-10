package com.ureca.snac.payment.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.wallet.service.WalletService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 결제와 관련된 단일 책임 내부 DB 상태 변경
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentInternalService {

    private final PaymentRepository paymentRepository;
    private final WalletService walletService;
    private final AssetRecorder assetRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final MemberRepository memberRepository;
    private final MeterRegistry meterRegistry;

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

        // FOR UPDATE 락으로 Payment 재조회 - 중복 취소 방지
        Payment lockedPayment = paymentRepository.findByIdForUpdate(payment.getId())
                .orElseThrow(PaymentNotFoundException::new);

        // 이미 취소된 경우 조기 종료 (멱등성)
        if (lockedPayment.getStatus() == PaymentStatus.CANCELED) {
            log.warn("[내부 처리] 이미 취소된 결제. 중복 처리 방지. paymentId: {}", payment.getId());
            return;
        }

        lockedPayment.cancel(cancelResponse.reason());
        log.info("[내부 처리] Payment 엔티티 상태 CANCELED 변경 완료");

        // 머니 잔액을 회수
        Long balanceAfter = walletService.withdrawMoney(member.getId(),
                lockedPayment.getAmount());
        log.info("[내부 처리] 지갑 머니 회수(출금) 완료 회원 ID : {}, 최종 잔액 : {}",
                member.getId(), balanceAfter);

        // 동기 직접 기록
        assetRecorder.recordMoneyRechargeCancel(
                member.getId(), lockedPayment.getId(), lockedPayment.getAmount(), balanceAfter);
        log.info("[내부 처리] 자산 변동 기록 직접 저장 완료. 회원 ID : {}", member.getId());
    }

    /**
     * 결제 취소 DB 처리 실패 시 보상 처리
     * Payment 상태를 CANCELED로 변경
     * Outbox 이벤트 발행 (Wallet 환불 + AssetHistory 기록은 비동기 처리)
     * <p>
     * REQUIRES_NEW: outer 트랜잭션 실패와 무관하게 보상 이벤트 커밋 보장
     * processCancellationInDB() 실패 시 outer 트랜잭션 롤백
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void compensateCancellationFailure(Payment payment, Member member,
                                              PaymentCancelResponse cancelResponse, Exception originalError) {
        Counter.builder("payment_compensation_triggered_total")
                .register(meterRegistry)
                .increment();

        try {
            log.warn("[결제 취소 보상] Payment 상태 복구 및 보상 이벤트 발행 시도. paymentId: {}",
                    payment.getId());

            // Payment 상태 CANCELED로 변경
            Payment freshPayment = paymentRepository.findById(payment.getId())
                    .orElseThrow(PaymentNotFoundException::new);
            freshPayment.cancel(cancelResponse.reason() + " [보상 처리]");
            paymentRepository.save(freshPayment);

            log.info("[결제 취소 보상] Payment 상태 CANCELED로 복구 완료. paymentId: {}",
                    payment.getId());

            // Outbox 이벤트 발행 (Wallet 출금(차감) 비동기로 처리)
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

            // 운영자 알림 이벤트 발행
            eventPublisher.publishEvent(new CompensationFailureEvent(
                    payment.getId(),
                    member.getId(),
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
     * 토스 취소 성공 후 DB 처리 실패 시 Wallet 환불 및 AssetHistory 기록
     * Listener에서 호출하여 self-invocation 문제 해결
     */
    @Transactional
    public void processCompensation(PaymentCancelCompensationEvent event) {

        // FOR UPDATE 락으로 Payment 재조회 - 중복 보상 방지 (멱등성)
        Payment lockedPayment = paymentRepository.findByIdForUpdate(event.paymentId())
                .orElseThrow(PaymentNotFoundException::new);

        // 이미 보상 처리된 경우 조기 종료
        if (lockedPayment.isCompensationCompleted()) {
            log.warn("[결제 취소 보상] 이미 처리된 보상. 중복 처리 방지. paymentId: {}", event.paymentId());
            return;
        }

        Member member = memberRepository.findById(event.memberId())
                .orElseThrow(MemberNotFoundException::new);

        // 환불 금액 출금 (이미 토스에서 취소됨 -> 시스템 잔액 차감 필요)
        Long balanceAfter = walletService.withdrawMoney(event.memberId(), event.amount());

        log.info("[결제 취소 보상] Wallet 출금 완료. memberId: {}, amount: {}, balanceAfter: {}",
                event.memberId(), event.amount(), balanceAfter);

        // 기록 저장
        assetRecorder.recordMoneyRechargeCancel(
                member.getId(), event.paymentId(), event.amount(), balanceAfter);

        log.info("[결제 취소 보상] AssetHistory 기록 완료. paymentId: {}", event.paymentId());

        // 보상 완료 플래그 설정
        lockedPayment.markCompensationCompleted();
    }
}
