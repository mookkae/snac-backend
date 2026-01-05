package com.ureca.snac.wallet.event;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.DomainEvent;
import com.ureca.snac.common.event.EventType;

/**
 * 지갑 생성 완료 이벤트
 * 웰컴 포인트 지급 트리거
 */
public record WalletCreatedEvent(
        Long walletId,
        Long memberId
) implements DomainEvent {

    @Override
    public EventType getEventType() {
        return EventType.WALLET_CREATED;
    }

    @Override
    public AggregateType getAggregateType() {
        return AggregateType.WALLET;
    }

    @Override
    public Long getAggregateId() {
        return walletId;
    }
}