package com.ureca.snac.member.event;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.DomainEvent;
import com.ureca.snac.common.event.EventType;

/**
 * 회원가입 완료 이벤트
 * 지갑 생성 및 웰컴 포인트 지급을 트리거
 */
public record MemberJoinEvent(Long memberId) implements DomainEvent {

    @Override
    public String getEventType() {
        return EventType.MEMBER_JOIN.getTypeName();
    }

    @Override
    public String getAggregateType() {
        return AggregateType.MEMBER.getTypeName();
    }

    @Override
    public Long getAggregateId() {
        return memberId;
    }
}
