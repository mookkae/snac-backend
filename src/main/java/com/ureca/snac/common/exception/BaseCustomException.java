package com.ureca.snac.common.exception;

import com.ureca.snac.common.BaseCode;
import lombok.Getter;

@Getter
public abstract class BaseCustomException extends RuntimeException {
    private final BaseCode baseCode;

    public BaseCustomException(BaseCode baseCode) {
        super(baseCode.getMessage());
        this.baseCode = baseCode;
    }

    public BaseCustomException(BaseCode baseCode, String customMessage) {
        super(customMessage);
        this.baseCode = baseCode;
    }

    // 원인 같이 받음
    public BaseCustomException(BaseCode baseCode, String customMessage, Throwable cause) {
        super(customMessage, cause); // 부모에 원인 전달 스택트레이스 위해서
        this.baseCode = baseCode;
    }
}