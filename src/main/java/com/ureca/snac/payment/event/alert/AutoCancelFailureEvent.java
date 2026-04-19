package com.ureca.snac.payment.event.alert;

/**
 * Auto-Cancel 실패 이벤트
 * <p>
 * Toss 승인 완료 + deposit() DB 실패 후 Auto-Cancel 과정에서 발행
 * Case A: Toss 취소 API 실패일 때는 DB: PENDING, Toss: DONE
 * Case B: Toss 취소 API 성공일 때는 DB: PENDING, Toss: CANCELED
 * 두 케이스 모두 대사 스케줄러가 자동 복구를 시도, 실패할 경우 수동 복구가 필요
 *
 * @param paymentId          결제 ID
 * @param memberId           회원 ID
 * @param amount             결제 금액
 * @param orderId            주문 ID
 * @param paymentKey         Toss 결제 키
 * @param dbErrorMessage     deposit() DB 처리 실패 오류
 * @param cancelErrorMessage Case A: Toss 취소 API 오류 / Case B: markAsCanceled DB 갱신 오류
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
