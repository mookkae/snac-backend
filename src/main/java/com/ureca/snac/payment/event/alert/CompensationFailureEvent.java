package com.ureca.snac.payment.event.alert;

import java.time.OffsetDateTime;

/**
 * 결제 취소 보상 처리 실패 이벤트
 * <p>
 * Toss에서는 취소되었으나 시스템 잔액이 갱신되지 않은 상태
 * 반드시 수동 복구 필요
 *
 * @param paymentId                결제 ID
 * @param memberId                 회원 ID
 * @param amount                   결제 금액
 * @param orderId                  주문 ID
 * @param paymentKey               Toss 결제 키
 * @param cancelReason             취소 사유
 * @param canceledAt               Toss 취소 완료 시각
 * @param originalErrorMessage     원본 DB 처리 실패 오류 메시지
 * @param compensationErrorMessage 보상 처리 실패 오류 메시지
 */
public record CompensationFailureEvent(
        Long paymentId,
        Long memberId,
        Long amount,
        String orderId,
        String paymentKey,
        String cancelReason,
        OffsetDateTime canceledAt,
        String originalErrorMessage,
        String compensationErrorMessage
) implements CriticalPaymentFailureEvent {
}
