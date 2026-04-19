package com.ureca.snac.infra.exception;

import com.ureca.snac.common.exception.ExternalApiException;

import static com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_CONFIG_ERROR;

public class TossInvalidApiKeyException extends ExternalApiException {

    public TossInvalidApiKeyException() {
        super(PAYMENT_GATEWAY_CONFIG_ERROR);
    }
}
