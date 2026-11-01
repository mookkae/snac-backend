package com.ureca.snac.wallet.exception;

import com.ureca.snac.common.exception.BusinessException;

import static com.ureca.snac.common.BaseCode.BALANCE_FROZEN;

public class FrozenBalanceException extends BusinessException {
    public FrozenBalanceException() {
        super(BALANCE_FROZEN);
    }
}
