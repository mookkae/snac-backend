package com.ureca.snac.infra.exception;

import com.ureca.snac.common.exception.ExternalApiException;

import static com.ureca.snac.common.BaseCode.PAYMENT_NOT_FOUND;

public class TossPaymentNotFoundException extends ExternalApiException {

    public TossPaymentNotFoundException() {
        super(PAYMENT_NOT_FOUND);
    }
}