package com.ureca.snac.payment.event;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.DomainEvent;
import com.ureca.snac.common.event.EventType;

import java.time.OffsetDateTime;

/**
 * 결제 취소 보상 이벤트
 * 토스 취소 성공 후 DB 처리 실패 시 발행
 * Wallet 환불 및 AssetHistory 기록을 비동기로 처리
 */
public record PaymentCancelCompensationEvent(
        Long paymentId,
        Long memberId,
        Long amount,
        String reason,
        OffsetDateTime canceledAt
) implements DomainEvent {

    @Override
    public EventType getEventType() {
        return EventType.PAYMENT_CANCEL_COMPENSATE;
    }

    @Override
    public AggregateType getAggregateType() {
        return AggregateType.PAYMENT;
    }

    @Override
    public Long getAggregateId() {
        return paymentId;
    }
}
