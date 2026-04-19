package com.ureca.snac.infra.exception;

import com.ureca.snac.common.exception.BusinessException;

import static com.ureca.snac.common.BaseCode.GATEWAY_INSUFFICIENT_BALANCE;

public class TossNotEnoughBalanceException extends BusinessException {

    public TossNotEnoughBalanceException() {
        super(GATEWAY_INSUFFICIENT_BALANCE);
    }
}
