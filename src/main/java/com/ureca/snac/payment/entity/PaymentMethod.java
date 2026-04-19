package com.ureca.snac.payment.entity;

import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * Toss Payments 결제 수단 enum
 * <p>
 * 각 수단이 자신의 취소 기간 정책을 직접 소유한다.
 * 새 수단 추가 시 {@code isCancellationExpired}를 반드시 구현해야 컴파일된다.
 */
@Getter
public enum PaymentMethod {

    CARD("카드") {
        @Override
        public boolean isCancellationExpired(ZonedDateTime paidAtKst, ZonedDateTime nowKst) {
            return false; // 별도 기한 정책 없음
        }
    },
    PHONE("휴대폰") {
        @Override
        public boolean isCancellationExpired(ZonedDateTime paidAtKst, ZonedDateTime nowKst) {
            // 결제 월과 현재 월이 다르면 취소 불가 (한국 결제 정책)
            return paidAtKst.getMonth() != nowKst.getMonth() ||
                    paidAtKst.getYear() != nowKst.getYear();
        }
    },
    VIRTUAL_ACCOUNT("가상계좌") {
        @Override
        public boolean isCancellationExpired(ZonedDateTime paidAtKst, ZonedDateTime nowKst) {
            // isMethodCancelable()에서 이미 차단되어 정상 흐름에서는 호출되지 않는다.
            // 만약 isMethodCancelable() 로직이 변경되어 이 경로에 진입하더라도
            // 가상계좌는 취소 불가 수단이므로 Fail-Safe로 true(기간 만료 = 취소 불가) 반환
            return true;
        }
    },
    TRANSFER("계좌이체") {
        @Override
        public boolean isCancellationExpired(ZonedDateTime paidAtKst, ZonedDateTime nowKst) {
            return false; // 별도 기한 정책 없음
        }
    },
    EASY_PAY("간편결제") {
        @Override
        public boolean isCancellationExpired(ZonedDateTime paidAtKst, ZonedDateTime nowKst) {
            return false; // 별도 기한 정책 없음
        }
    },
    UNKNOWN(null) {
        @Override
        public boolean isCancellationExpired(ZonedDateTime paidAtKst, ZonedDateTime nowKst) {
            return true; // 수단 불명 → 안전상 취소 불가
        }
    };

    private final String tossApiValue;

    PaymentMethod(String tossApiValue) {
        this.tossApiValue = tossApiValue;
    }

    /**
     * 결제 수단별 취소 기간 만료 여부
     *
     * @param paidAtKst 결제 시각 (KST)
     * @param nowKst    현재 시각 (KST)
     * @return true면 기간 만료 (취소 불가)
     */
    public abstract boolean isCancellationExpired(ZonedDateTime paidAtKst, ZonedDateTime nowKst);

    /**
     * Toss API 응답 문자열을 enum으로 변환
     */
    public static PaymentMethod fromTossMethod(String method) {
        if (method == null) {
            return UNKNOWN;
        }
        for (PaymentMethod pm : values()) {
            if (method.equals(pm.tossApiValue)) {
                return pm;
            }
        }
        return UNKNOWN;
    }
}
