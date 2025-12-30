package com.ureca.snac.wallet.dto;

import com.ureca.snac.wallet.entity.Wallet;

public record CompositeBalanceResult(
        long moneyBalance,
        long moneyEscrow,
        long pointBalance,
        long pointEscrow
) {
    public static CompositeBalanceResult from(Wallet wallet) {
        return new CompositeBalanceResult(
                wallet.getMoneyBalance(),
                wallet.getMoneyEscrow(),
                wallet.getPointBalance(),
                wallet.getPointEscrow()
        );
    }
}
