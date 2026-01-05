package com.ureca.snac.common.exception;

import static com.ureca.snac.common.BaseCode.INTERNAL_SERVER_ERROR;

/**
 * 알 수 없는 이벤트 타입 예외
 * 설정되지 않은 이벤트 타입이 사용될 때 발생
 */
public class UnknownEventTypeException extends InternalServerException {

    public UnknownEventTypeException(String typeName) {
        super(INTERNAL_SERVER_ERROR, "알 수 없는 이벤트 타입: " + typeName);
    }
}