package com.ureca.snac.payment.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record PaymentFailureRequest(
        @Schema(description = "토스 페이먼츠가 반환한 에러코드",
                example = "INVALID_CARD_INFO")
        @NotBlank
        String errorCode,

        @Schema(description = "토스페이먼츠가 반환한 에러 메시지",
                example = "카드 정보가 유효하지 않습니다")
        @NotBlank
        String errorMessage,

        @Schema(description = "실패한 주문의 ID")
        @NotBlank
        String orderId,

        @Schema(description = "멱등키 (클라이언트 생성 UUID, 중복 요청 방지용)",
                example = "550e8400-e29b-41d4-a716-446655440000")
        String idempotencyKey
) {
    /**
     * 멱등키가 없으면 orderId 기반으로 생성
     */
    public String resolveIdempotencyKey() {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return idempotencyKey;
        }
        return "fail:" + orderId;
    }
}
