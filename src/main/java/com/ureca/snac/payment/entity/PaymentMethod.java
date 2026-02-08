package com.ureca.snac.payment.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Toss Payments 결제 수단 enum
 * String 리터럴 대신 타입 안전한 enum 사용
 */
@Slf4j
@Getter
@RequiredArgsConstructor
public enum PaymentMethod {
    CARD("카드"),
    PHONE("휴대폰"),
    VIRTUAL_ACCOUNT("가상계좌"),
    TRANSFER("계좌이체"),
    EASY_PAY("간편결제"),
    UNKNOWN(null);

    private final String tossApiValue;

    /**
     * Toss API 응답 문자열을 enum으로 변환
     */
    public static PaymentMethod fromTossMethod(String method) {
        if (method == null) {
            log.warn("[PaymentMethod] 토스 결제 수단이 null로 전달됨. UNKNOWN으로 매핑");
            return UNKNOWN;
        }
        for (PaymentMethod pm : values()) {
            if (method.equals(pm.tossApiValue)) {
                return pm;
            }
        }
        log.warn("[PaymentMethod] 알 수 없는 토스 결제 수단: '{}'. UNKNOWN으로 매핑", method);
        return UNKNOWN;
    }
}
