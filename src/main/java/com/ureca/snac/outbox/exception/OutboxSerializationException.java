package com.ureca.snac.outbox.exception;

import com.ureca.snac.common.exception.InternalServerException;

import static com.ureca.snac.common.BaseCode.INTERNAL_SERVER_ERROR;

public class OutboxSerializationException extends InternalServerException {

    public OutboxSerializationException(String message, Throwable cause) {
        super(INTERNAL_SERVER_ERROR, message, cause);
    }
}