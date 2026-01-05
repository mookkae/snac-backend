package com.ureca.snac.common.event;

import com.ureca.snac.common.exception.UnknownAggregateTypeException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * Aggregate 타입별 메시징 설정
 * Exchange와 타입 문자열을 중앙 관리
 * OCP 원칙: 새 도메인 추가 시 Enum만 수정
 */
@Getter
@RequiredArgsConstructor
public enum AggregateType {
    MEMBER("MEMBER", "member.exchange"),
    WALLET("WALLET", "wallet.exchange"),
    PAYMENT("PAYMENT", "payment.exchange"),
    TRADE("TRADE", "trade.exchange");

    private final String typeName;      // "MEMBER"
    private final String exchange;      // "member.exchange"

    /**
     * 타입 이름 → Enum 매핑 (O(1) 조회)
     */
    private static final Map<String, AggregateType> TYPE_MAP;

    static {
        TYPE_MAP = new HashMap<>();
        for (AggregateType type : values()) {
            TYPE_MAP.put(type.typeName, type);
        }
    }

    /**
     * 타입 이름으로 AggregateType 조회
     *
     * @param typeName 타입 이름 (예: "MEMBER")
     * @return AggregateType
     * @throws UnknownAggregateTypeException 알 수 없는 타입
     */
    public static AggregateType from(String typeName) {
        AggregateType type = TYPE_MAP.get(typeName);

        if (type == null) {
            throw new UnknownAggregateTypeException(typeName);
        }

        return type;
    }
}