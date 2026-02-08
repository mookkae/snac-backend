package com.ureca.snac.config;

import com.ureca.snac.common.event.AggregateType;

import java.util.Map;

/**
 * 도메인과 인프라분리
 * AggregateType은 도메인 레벨
 * Exchange 매핑은 인프라 계층
 * Exchange 결정 시 사용
 */
public class AggregateExchangeMapper {

    private static final Map<AggregateType, String> EXCHANGE_MAP = Map.of(
            AggregateType.MEMBER, RabbitMQQueue.MEMBER_EXCHANGE,
            AggregateType.WALLET, RabbitMQQueue.WALLET_EXCHANGE,
            AggregateType.PAYMENT, RabbitMQQueue.PAYMENT_EXCHANGE
    );

    public static String getExchange(AggregateType aggregateType) {
        String exchange = EXCHANGE_MAP.get(aggregateType);

        if (exchange == null) {
            throw new IllegalArgumentException(
                    "Exchange 매핑이 존재하지 않습니다. aggregateType: " + aggregateType
            );
        }

        return exchange;
    }

    private AggregateExchangeMapper() {
        // 인스턴스화 방지
    }
}