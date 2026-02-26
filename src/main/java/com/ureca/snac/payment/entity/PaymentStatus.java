package com.ureca.snac.payment.entity;

public enum PaymentStatus {
    PENDING,
    SUCCESS,
    CANCEL_REQUESTED,
    CANCELED
    /*
     * PENDING: 결제 대기중
     * SUCCESS : 결제 성공
     * CANCEL_REQUESTED : 결제 취소 요청 (토스 API 호출 전 의도 기록)
     * CANCELED : 결제 취소
     */
}
