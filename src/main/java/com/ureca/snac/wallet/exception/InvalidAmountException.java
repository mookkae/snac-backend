package com.ureca.snac.wallet.exception;

import com.ureca.snac.common.exception.BusinessException;

import static com.ureca.snac.common.BaseCode.INVALID_AMOUNT;

public class InvalidAmountException extends BusinessException {
    public InvalidAmountException() {
        super(INVALID_AMOUNT);
    }
}