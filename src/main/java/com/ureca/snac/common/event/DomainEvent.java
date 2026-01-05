package com.ureca.snac.common.event;

/**
 * 모든 도메인 이벤트가 구현해야 하는 인터페이스
 * Outbox 패턴의 메타데이터 제공
 */
public interface DomainEvent {

    /**
     * Aggregate ID
     * 이벤트가 발생한 엔티티의 식별자
     *
     * @return memberId, walletId 등
     */
    Long getAggregateId();


    /**
     * Aggregate 타입
     * Exchange 결정에 사용
     *
     * @return AggregateType Enum (AggregateType.MEMBER, WALLET, PAYMENT 등)
     */
    AggregateType getAggregateType();

    /**
     * 이벤트 타입 (클래스명)
     * Routing Key 결정에 사용
     *
     * @return EventType Enum (EventType.MEMBER_JOIN, WALLET_CREATED 등)
     */
    EventType getEventType();
}