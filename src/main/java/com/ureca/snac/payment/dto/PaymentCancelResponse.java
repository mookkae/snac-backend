package com.ureca.snac.payment.dto;

import lombok.Builder;

import java.time.OffsetDateTime;

/**
 * 결제 취소 시 서비스 계층이 반환하는 DTO
 *
 * @param paymentKey     결제 취소할 고유 키
 * @param canceledAmount 실제로 취소된 금액 (처리 중일 때는 null)
 * @param canceledAt     실제로 취소한 시각 (처리 중일 때는 null)
 * @param reason         취소 사유
 * @param processing     true = 취소 처리 중 (Toss 결과 미확정), false = 취소 완료
 */
@Builder
public record PaymentCancelResponse(
        String paymentKey,
        Long canceledAmount,
        OffsetDateTime canceledAt,
        String reason,
        boolean processing
) {
    /**
     * Toss 결과 미확정 시 (5xx/timeout 3회 소진) 반환하는 "처리 중" 응답
     * frozen + CANCEL_REQUESTED 유지 상태이므로 클라이언트는 잠시 후 재조회 필요
     */
    public static PaymentCancelResponse ofProcessing(String paymentKey) {
        return PaymentCancelResponse.builder()
                .paymentKey(paymentKey)
                .reason("취소 처리 중")
                .processing(true)
                .build();
    }
}
