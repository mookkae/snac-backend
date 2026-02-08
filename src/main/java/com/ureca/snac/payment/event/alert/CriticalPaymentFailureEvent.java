package com.ureca.snac.payment.event.alert;

/**
 * 결제 관련 실패 이벤트 인터페이스
 * <p>
 * 운영자 알림이 반드시 필요한 치명적인 실패 상황
 * AutoCancelFailureEvent: Toss 승인 성공 + DB 실패 + 자동 취소 실패
 * CompensationFailureEvent: Toss 결제 취소 성공 + 우리 DB 보상 처리 실패
 */
public sealed interface CriticalPaymentFailureEvent
        permits AutoCancelFailureEvent, CompensationFailureEvent {

    Long paymentId();

    Long memberId();

    Long amount();

    String orderId();
}
