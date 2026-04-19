package com.ureca.snac.payment.port.out.exception;

import static com.ureca.snac.common.BaseCode.GATEWAY_INVALID_CARD_INFO;

public class InvalidPaymentCardException extends PortGatewayException {

    public InvalidPaymentCardException() {
        super(GATEWAY_INVALID_CARD_INFO);
    }
}
