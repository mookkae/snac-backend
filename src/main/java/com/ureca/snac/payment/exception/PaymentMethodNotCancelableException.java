package com.ureca.snac.payment.exception;

import com.ureca.snac.common.exception.BusinessException;

import static com.ureca.snac.common.BaseCode.PAYMENT_METHOD_NOT_CANCELABLE;

public class PaymentMethodNotCancelableException extends BusinessException {
    public PaymentMethodNotCancelableException() {
        super(PAYMENT_METHOD_NOT_CANCELABLE);
    }
}