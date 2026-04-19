package com.ureca.snac.infra.exception;

import com.ureca.snac.common.exception.ExternalApiException;

import static com.ureca.snac.common.BaseCode.PAYMENT_NOT_CANCELLABLE;

public class TossNotCancelablePaymentException extends ExternalApiException {

    public TossNotCancelablePaymentException() {
        super(PAYMENT_NOT_CANCELLABLE);
    }
}
