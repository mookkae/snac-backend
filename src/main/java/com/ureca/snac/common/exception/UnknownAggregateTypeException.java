package com.ureca.snac.common.exception;

import static com.ureca.snac.common.BaseCode.INTERNAL_SERVER_ERROR;

/**
 * 알 수 없는 Aggregate 타입 예외
 * 설정되지 않은 도메인 타입이 사용될 때 발생
 */
public class UnknownAggregateTypeException extends InternalServerException {

    public UnknownAggregateTypeException(String typeName) {
        super(INTERNAL_SERVER_ERROR, "알 수 없는 Aggregate 타입: " + typeName);
    }
}