package com.ureca.snac.common.event;

import com.ureca.snac.common.exception.UnknownEventTypeException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * 이벤트 타입별 Routing Key 매핑
 * OCP 원칙: 새 이벤트 추가 시 Enum만 수정
 */
@Getter
@RequiredArgsConstructor
public enum EventType {
    MEMBER_JOIN("MemberJoinEvent", "member.joined"),
    WALLET_CREATED("WalletCreatedEvent", "wallet.created"),
    PAYMENT_CANCEL_COMPENSATE("PaymentCancelCompensateEvent", "payment.cancel.compensate");

    private final String typeName;      // "MemberJoinEvent"
    private final String routingKey;    // "member.joined"

    /**
     * 타입 이름 → Enum 매핑 (O(1) 조회)
     */
    private static final Map<String, EventType> TYPE_MAP;

    static {
        TYPE_MAP = new HashMap<>();
        for (EventType type : values()) {
            TYPE_MAP.put(type.typeName, type);
        }
    }

    /**
     * 타입 이름으로 EventType 조회
     *
     * @param typeName 이벤트 타입 이름 (예: "MemberJoinEvent")
     * @return EventType
     * @throws UnknownEventTypeException 알 수 없는 타입
     */
    public static EventType from(String typeName) {
        EventType type = TYPE_MAP.get(typeName);

        if (type == null) {
            throw new UnknownEventTypeException(typeName);
        }

        return type;
    }
}