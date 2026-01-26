package com.ureca.snac.infra.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Toss Payments API 취소 응답 매핑
 * 내부용 Payment랑 구분해야됨
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossCancelResponse(
        String paymentKey,
        String orderId,
        List<Cancel> cancels
) {
    public TossCancelResponse {
        Objects.requireNonNull(paymentKey, "paymentKey must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        // cancels는 취소 내역이 없을 수 있으므로 null 허용하되, 빈 리스트로 정규화
        if (cancels == null) {
            cancels = List.of();
        }
    }

    /**
     * 가장 최근 취소 정보 조회
     * @return 최근 Cancel 또는 null (취소 내역 없음)
     */
    public Cancel getLatestCancel() {
        if (cancels.isEmpty()) {
            return null;
        }
        return cancels.get(cancels.size() - 1);
    }

    /**
     * 취소 내역 존재 여부
     */
    public boolean hasCancels() {
        return !cancels.isEmpty();
    }

    public record Cancel(
            Long cancelAmount,
            String cancelReason,
            OffsetDateTime canceledAt
    ) {
    }
}
