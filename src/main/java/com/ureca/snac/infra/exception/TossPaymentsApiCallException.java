package com.ureca.snac.infra.exception;

import com.ureca.snac.common.exception.ExternalApiException;

import static com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_API_ERROR;

public class TossPaymentsApiCallException extends ExternalApiException {

    public TossPaymentsApiCallException() {
        super(PAYMENT_GATEWAY_API_ERROR);
    }
}
