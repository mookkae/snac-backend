package com.ureca.snac.infra;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TossErrorCode {
    // 4xx 사용자 입력/상태 문제
    INVALID_CARD_EXPIRATION("INVALID_CARD_EXPIRATION", false),
    INVALID_CARD_NUMBER("INVALID_CARD_NUMBER", false),
    INVALID_CARD_INFO("INVALID_CARD_INFO", false),
    INVALID_CVV("INVALID_CVV", false),
    REJECT_CARD_COMPANY("REJECT_CARD_COMPANY", false),
    NOT_ENOUGH_BALANCE("NOT_ENOUGH_BALANCE", false),
    INVALID_STOPPED_CARD("INVALID_STOPPED_CARD", false),
    EXCEED_MAX_DAILY_PAYMENT_COUNT("EXCEED_MAX_DAILY_PAYMENT_COUNT", false),
    EXCEED_MAX_PAYMENT_AMOUNT("EXCEED_MAX_PAYMENT_AMOUNT", false),
    INVALID_AMOUNT("INVALID_AMOUNT", false),
    BELOW_MINIMUM_AMOUNT("BELOW_MINIMUM_AMOUNT", false),

    // 인증/설정 에러 시스템 설정 문제
    INVALID_API_KEY("INVALID_API_KEY", false),
    UNAUTHORIZED_KEY("UNAUTHORIZED_KEY", false),
    INVALID_AUTHORIZATION("INVALID_AUTHORIZATION", false),
    FORBIDDEN_REQUEST("FORBIDDEN_REQUEST", false),
    NOT_FOUND_PAYMENT("NOT_FOUND_PAYMENT", false),
    NOT_FOUND_PAYMENT_SESSION("NOT_FOUND_PAYMENT_SESSION", false),

    // 5xx 서버 에러  일시적 장애 재시도
    PROVIDER_ERROR("PROVIDER_ERROR", true),
    FAILED_INTERNAL_SYSTEM_PROCESSING("FAILED_INTERNAL_SYSTEM_PROCESSING", true),
    UNKNOWN_PAYMENT_ERROR("UNKNOWN_PAYMENT_ERROR", true),
    SERVICE_UNAVAILABLE("SERVICE_UNAVAILABLE", true),
    TIMEOUT("TIMEOUT", true),
    PG_PROVIDER_ERROR("PG_PROVIDER_ERROR", true),

    // 이미 처리된 케이스
    ALREADY_PROCESSED_PAYMENT("ALREADY_PROCESSED_PAYMENT", false),
    ALREADY_CANCELED_PAYMENT("ALREADY_CANCELED_PAYMENT", false),
    NOT_CANCELABLE_PAYMENT("NOT_CANCELABLE_PAYMENT", false),

    // 모르는 에러 코드 걍 재시도
    UNKNOWN("UNKNOWN_ERROR", true);

    private final String code;
    private final boolean retryable;

    public static TossErrorCode fromCode(String codeString) {
        for (TossErrorCode errorCode : TossErrorCode.values()) {
            if (errorCode.getCode().equals(codeString)) {
                return errorCode;
            }
        }
        return UNKNOWN;
    }
}
