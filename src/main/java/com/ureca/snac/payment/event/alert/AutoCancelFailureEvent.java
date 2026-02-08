package com.ureca.snac.payment.event.alert;

/**
 * Auto-Cancel 실패 이벤트
 * <p>
 * Toss 결제 승인 응답 + 우리 DB 처리 실패 + 자동 취소 API 요청 실패 시 발행 되는 이벤트
 * 반드시 수동 복구 필요
 *
 * @param paymentId          결제 ID
 * @param memberId           회원 ID
 * @param amount             결제 금액
 * @param orderId            주문 ID
 * @param paymentKey         Toss 결제 키
 * @param dbErrorMessage     DB 처리 실패 오류 메시지
 * @param cancelErrorMessage 자동 취소 실패 오류 메시지
 */
public record AutoCancelFailureEvent(
        Long paymentId,
        Long memberId,
        Long amount,
        String orderId,
        String paymentKey,
        String dbErrorMessage,
        String cancelErrorMessage
) implements CriticalPaymentFailureEvent {
}
