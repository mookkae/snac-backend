package com.ureca.snac.payment.exception;

import com.ureca.snac.common.exception.BusinessException;

import static com.ureca.snac.common.BaseCode.PAYMENT_ALREADY_SUCCESS;

public class PaymentAlreadySuccessException extends BusinessException {
    public PaymentAlreadySuccessException() {
        super(PAYMENT_ALREADY_SUCCESS);
    }
}
