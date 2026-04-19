package com.ureca.snac.wallet.dto;

import com.ureca.snac.wallet.entity.Wallet;

public record WalletAvailableBalanceResponse(
        long moneyBalance,
        long pointBalance
) {
    public static WalletAvailableBalanceResponse from(Wallet wallet) {
        return new WalletAvailableBalanceResponse(
                wallet.getMoneyBalance(),
                wallet.getPointBalance()
        );
    }
}
