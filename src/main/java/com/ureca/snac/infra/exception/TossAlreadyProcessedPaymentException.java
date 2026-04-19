package com.ureca.snac.infra.exception;

import com.ureca.snac.common.exception.ExternalApiException;

import static com.ureca.snac.common.BaseCode.PAYMENT_ALREADY_SUCCESS;

public class TossAlreadyProcessedPaymentException extends ExternalApiException {

    public TossAlreadyProcessedPaymentException() {
        super(PAYMENT_ALREADY_SUCCESS);
    }
}
