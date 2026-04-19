package com.ureca.snac.payment.port.out.exception;

import static com.ureca.snac.common.BaseCode.GATEWAY_INSUFFICIENT_BALANCE;

public class InsufficientCardBalanceException extends PortGatewayException {

    public InsufficientCardBalanceException() {
        super(GATEWAY_INSUFFICIENT_BALANCE);
    }
}
