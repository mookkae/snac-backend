package com.ureca.snac.payment.exception;

import com.ureca.snac.common.exception.BusinessException;

import static com.ureca.snac.common.BaseCode.PAYMENT_CANCELLATION_IN_PROGRESS;

public class PaymentCancellationInProgressException extends BusinessException {

    public PaymentCancellationInProgressException() {
        super(PAYMENT_CANCELLATION_IN_PROGRESS);
    }
}
