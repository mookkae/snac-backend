package com.ureca.snac.infra.exception;

import com.ureca.snac.common.exception.ExternalApiException;

import static com.ureca.snac.common.BaseCode.PAYMENT_ALREADY_CANCELED;

public class TossAlreadyCanceledPaymentException extends ExternalApiException {

    public TossAlreadyCanceledPaymentException() {
        super(PAYMENT_ALREADY_CANCELED);
    }
}
