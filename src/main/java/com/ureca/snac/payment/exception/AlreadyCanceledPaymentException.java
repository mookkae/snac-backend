package com.ureca.snac.payment.exception;

import com.ureca.snac.common.exception.BusinessException;

import static com.ureca.snac.common.BaseCode.PAYMENT_ALREADY_CANCELED;

public class AlreadyCanceledPaymentException extends BusinessException {
    public AlreadyCanceledPaymentException() {
        super(PAYMENT_ALREADY_CANCELED);
    }
}
