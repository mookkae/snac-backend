package com.ureca.snac.wallet.dto;

import com.ureca.snac.wallet.entity.Wallet;

/**
 * 지갑 요약 응답
 * moneyBalance는 표시용 머니(가용 + 취소 처리 중)이다. 환불이 끝나기 전에 잔액이 줄어 보이지 않게 한다
 */
public record WalletAvailableBalanceResponse(
        long moneyBalance,
        long pointBalance
) {
    public static WalletAvailableBalanceResponse empty() {
        return new WalletAvailableBalanceResponse(0L, 0L);
    }

    public static WalletAvailableBalanceResponse from(Wallet wallet) {
        return new WalletAvailableBalanceResponse(
                wallet.getMoneyOwned(),
                wallet.getPointBalance()
        );
    }
}
