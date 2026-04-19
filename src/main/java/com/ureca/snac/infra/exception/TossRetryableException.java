package com.ureca.snac.infra.exception;

import com.ureca.snac.common.exception.ExternalApiException;

import static com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_TRANSIENT_ERROR;

public class TossRetryableException extends ExternalApiException {

    public TossRetryableException() {
        super(PAYMENT_GATEWAY_TRANSIENT_ERROR);
    }
}
