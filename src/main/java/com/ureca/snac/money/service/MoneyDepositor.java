package com.ureca.snac.money.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.money.entity.MoneyRecharge;
import com.ureca.snac.money.repository.MoneyRechargeRepository;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentMethod;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.exception.UnsupportedPaymentMethodException;
import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 머니 입금 DB 처리 담당
 * <p>
 * FOR UPDATE 락으로 Payment 상태 확인하여 중복 처리 방지 (멱등성)
 *
 * @Retryable은 MoneyDepositorRetryFacade에서 관리
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
    public void deposit(Long paymentId, Long memberId, PaymentConfirmResult confirmResult) {
        log.info("[머니 입금 처리] DB 상태 변경 시작. paymentId : {}", paymentId);

        // FOR UPDATE 락으로 Payment 재조회
        Payment lockedPayment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(PaymentNotFoundException::new);

        PaymentMethod method = PaymentMethod.fromTossMethod(confirmResult.method());
        if (method == PaymentMethod.UNKNOWN) {
            log.error("[머니 입금 처리] 알 수 없는 결제 수단. paymentId: {}, method: {}",
                    paymentId, confirmResult.method());
            throw new UnsupportedPaymentMethodException();
        }

        lockedPayment.complete(confirmResult.paymentKey(), method, confirmResult.approvedAt());
        log.info("[머니 입금 처리] Payment 엔티티 SUCCESS 변경 완료");

        MoneyRecharge recharge = MoneyRecharge.create(lockedPayment);
        moneyRechargeRepository.save(recharge);
        log.info("[머니 입금 처리] MoneyRecharge 기록 생성 완료. rechargeId : {}", recharge.getId());

        Long balanceAfter = walletService.depositMoney(memberId, lockedPayment.getAmount());
        log.info("[머니 입금 처리] 지갑 머니 입금 완료. memberId : {} , 최종 잔액 : {}", memberId, balanceAfter);

        assetRecorder.recordMoneyRecharge(memberId, lockedPayment.getId(), recharge.getPaidAmountWon(), balanceAfter);
        log.info("[머니 입금 처리] 자산 변동 기록 직접 저장 완료.");
    }
}
