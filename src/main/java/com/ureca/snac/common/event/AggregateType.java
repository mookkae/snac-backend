package com.ureca.snac.common.event;

import com.ureca.snac.common.exception.UnknownAggregateTypeException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

// Aggregate 타입 도메인 문자열
@Getter
@RequiredArgsConstructor
public enum AggregateType {
    MEMBER("MEMBER"),
    WALLET("WALLET"),
    PAYMENT("PAYMENT"),
    TRADE("TRADE");

    private final String typeName;  // "MEMBER", "WALLET",

    // 타입 Enum 매핑 O(1) 조회
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
     * @param typeName 타입 이름 ("MEMBER", "WALLET",)
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