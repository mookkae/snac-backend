package com.ureca.snac.payment.exception;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.infra.TossErrorCode;
import lombok.Getter;

import static com.ureca.snac.common.BaseCode.TOSS_API_RETRYABLE_ERROR;

/**
 * 토스 API의 일시적 장애로 인한 재시도 가능 예외
 * 5xx 에러, 타임아웃 등 일시적 장애 시 발생
 */
@Getter
public class TossRetryableException extends ExternalApiException {

    private final TossErrorCode errorCode;

    public TossRetryableException(TossErrorCode errorCode) {
        super(TOSS_API_RETRYABLE_ERROR);
        this.errorCode = errorCode;
    }

    public TossRetryableException(TossErrorCode errorCode, String customMessage) {
        super(TOSS_API_RETRYABLE_ERROR, customMessage);
        this.errorCode = errorCode;
    }
}
