package com.ureca.snac.money.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.entity.MoneyRecharge;
import com.ureca.snac.money.repository.MoneyRechargeRepository;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentMethod;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 머니 입금 DB 처리 담당
 * FOR UPDATE 락으로 Payment 상태 확인하여 중복 처리 방지 (멱등성)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MoneyDepositor {

    private final PaymentRepository paymentRepository;
    private final MoneyRechargeRepository moneyRechargeRepository;
    private final WalletService walletService;
    private final AssetRecorder assetRecorder;

    @Transactional
    public void deposit(Payment payment, Member member, TossConfirmResponse tossConfirmResponse) {
        log.info("[머니 입금 처리] DB 상태 변경 시작. paymentId : {}", payment.getId());

        // FOR UPDATE 락으로 Payment 재조회 - race condition 방지
        // 이 시점에 락을 획득하여 동시 요청 직렬화
        Payment lockedPayment = paymentRepository.findByIdForUpdate(payment.getId())
                .orElseThrow(PaymentNotFoundException::new);

        // 락 획득 후 상태 확인 - 이미 처리된 경우 조기 종료
        if (lockedPayment.getStatus() != PaymentStatus.PENDING) {
            log.warn("[머니 입금 처리] 이미 처리된 결제. 중복 처리 방지. paymentId: {}, status: {}",
                    payment.getId(), lockedPayment.getStatus());
            return;
        }

        // 락이 걸린 영속 객체를 사용하여 모든 작업 수행
        lockedPayment.complete(tossConfirmResponse.paymentKey(),
                PaymentMethod.fromTossMethod(tossConfirmResponse.method()),
                tossConfirmResponse.approvedAt()
        );
        log.info("[머니 입금 처리] Payment 엔티티 SUCCESS 변경 완료");

        MoneyRecharge recharge = MoneyRecharge.create(lockedPayment);
        moneyRechargeRepository.save(recharge);
        log.info("[머니 입금 처리] MoneyRecharge 기록 생성 완료. rechargeId : {}", recharge.getId());

        Long balanceAfter = walletService.depositMoney(member.getId(), lockedPayment.getAmount());
        log.info("[머니 입금 처리] 지갑 머니 입금 완료. member Id : {} , 최종 잔액 : {}",
                member.getId(), balanceAfter);

        // 동기 직접 기록
        assetRecorder.recordMoneyRecharge(
                member.getId(), lockedPayment.getId(), recharge.getPaidAmountWon(), balanceAfter);
        log.info("[머니 입금 처리] 자산 변동 기록 직접 저장 완료.");
    }
}
