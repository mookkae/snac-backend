package com.ureca.snac.payment.exception;

import com.ureca.snac.common.BaseCode;
import com.ureca.snac.common.exception.BusinessException;

public class UnsupportedPaymentMethodException extends BusinessException {
    public UnsupportedPaymentMethodException() {
        super(BaseCode.INVALID_INPUT);
    }
}