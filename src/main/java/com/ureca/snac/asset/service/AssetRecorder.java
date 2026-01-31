package com.ureca.snac.asset.service;

import com.ureca.snac.asset.entity.AssetType;

/**
 * 자산 내역 기록 서비스 인터페이스
 * 모든 도메인에서 동기적으로 자산 변동 내역을 기록할 때 사용
 */
public interface AssetRecorder {

    // 머니 충전 내역 기록
    void recordMoneyRecharge(Long memberId, Long paymentId, Long amount, Long balanceAfter);

    // 머니 충전 취소 내역 기록
    void recordMoneyRechargeCancel(Long memberId, Long paymentId, Long amount, Long balanceAfter);

    // 거래 구매 내역 기록
    void recordTradeBuy(Long memberId, Long tradeId, String title,
                        AssetType assetType, Long amount, Long balanceAfter);

    // 거래 판매 수익 내역 기록
    void recordTradeSell(Long memberId, Long tradeId, String title,
                         Long amount, Long balanceAfter);

    // 거래 취소 환불 내역 기록
    void recordTradeCancelRefund(Long memberId, Long tradeId, String title,
                                 AssetType assetType, Long amount, Long balanceAfter);

    // 회원가입 보너스 내역 기록
    void recordSignupBonus(Long memberId, Long amount, Long balanceAfter);

    // 거래 완료 보너스 내역 기록
    void recordTradeCompletionBonus(Long memberId, Long tradeId, Long amount, Long balanceAfter);

    // 정산 출금 내역 기록
    void recordSettlement(Long memberId, Long settlementId, Long amount, Long balanceAfter);
}
