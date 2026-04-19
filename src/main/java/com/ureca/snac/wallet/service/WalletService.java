package com.ureca.snac.wallet.service;

import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.dto.WalletAvailableBalanceResponse;

public interface WalletService {

    // 지갑 생성
    void createWallet(Long memberId);

    // 머니 입출금
    long depositMoney(Long memberId, long amount);

    long withdrawMoney(Long memberId, long amount);

    // 포인트 입금
    long depositPoint(Long memberId, long amount);

    // 복합 에스크로
    CompositeBalanceResult moveCompositeToEscrow(Long memberId, long moneyAmount, long pointAmount);

    CompositeBalanceResult cancelCompositeEscrow(Long memberId, long moneyAmount, long pointAmount);

    CompositeBalanceResult deductCompositeEscrow(Long memberId, long moneyAmount, long pointAmount);

    // 머니 동결 (충전 결제 취소 환불 절차용)
    long freezeMoney(Long memberId, long amount);

    long unfreezeMoney(Long memberId, long amount);

    long deductFrozenMoney(Long memberId, long amount);

    // 조회
    long getMoneyBalance(Long memberId);

    // 특정 회원의 지갑 요약 정보(머니, 포인트 잔액) 조회
    WalletAvailableBalanceResponse getWalletSummary(Long memberId);
}