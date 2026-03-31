package com.ureca.snac.wallet.service;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.dto.WalletSummaryResponse;

public interface WalletService {

    // 지갑 생성
    void createWallet(Member member);

    // 머니 입출금
    Long depositMoney(Long memberId, long amount);

    Long withdrawMoney(Long memberId, long amount);

    // 머니 에스크로
    long moveMoneyToEscrow(Long memberId, long amount);

    long releaseMoneyEscrow(Long memberId, long amount);

    long deductMoneyEscrow(Long memberId, long amount);

    // 포인트 입출금
    Long depositPoint(Long memberId, long amount);

    Long withdrawPoint(Long memberId, long amount);

    // 포인트 에스크로
    long movePointToEscrow(Long memberId, long amount);

    long releasePointEscrow(Long memberId, long amount);

    long deductPointEscrow(Long memberId, long amount);

    // 복합 결제
    CompositeBalanceResult withdrawComposite(Long memberId, long moneyAmount, long pointAmount);

    CompositeBalanceResult moveCompositeToEscrow(Long memberId, long moneyAmount, long pointAmount);

    CompositeBalanceResult releaseCompositeEscrow(Long memberId, long moneyAmount, long pointAmount);

    CompositeBalanceResult deductCompositeEscrow(Long memberId, long moneyAmount, long pointAmount);

    // 조회
    long getMoneyBalance(Long memberId);

    long getMoneyEscrow(Long memberId);

    long getPointBalance(Long memberId);

    long getPointEscrow(Long memberId);

    // 특정 회원의 지갑 요약 정보(머니, 포인트 잔액) 조회
    WalletSummaryResponse getWalletSummary(String email);
}