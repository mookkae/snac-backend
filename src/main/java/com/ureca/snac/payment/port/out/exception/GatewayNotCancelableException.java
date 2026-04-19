package com.ureca.snac.payment.port.out.exception;

import static com.ureca.snac.common.BaseCode.PAYMENT_NOT_CANCELLABLE;

public class GatewayNotCancelableException extends PortGatewayException {

    public GatewayNotCancelableException() {
        super(PAYMENT_NOT_CANCELLABLE);
    }
}
