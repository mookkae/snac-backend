package com.ureca.snac.payment.port.out.exception;

import com.ureca.snac.common.exception.ExternalApiException;

import static com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_TRANSIENT_ERROR;


public class GatewayTransientException extends ExternalApiException {

    public GatewayTransientException(Throwable cause) {
        super(PAYMENT_GATEWAY_TRANSIENT_ERROR, PAYMENT_GATEWAY_TRANSIENT_ERROR.getMessage(), cause);
    }
}