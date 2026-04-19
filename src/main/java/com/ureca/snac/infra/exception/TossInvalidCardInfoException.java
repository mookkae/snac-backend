package com.ureca.snac.infra.exception;

import com.ureca.snac.common.exception.BusinessException;

import static com.ureca.snac.common.BaseCode.GATEWAY_INVALID_CARD_INFO;

public class TossInvalidCardInfoException extends BusinessException {

    public TossInvalidCardInfoException() {
        super(GATEWAY_INVALID_CARD_INFO);
    }
}
