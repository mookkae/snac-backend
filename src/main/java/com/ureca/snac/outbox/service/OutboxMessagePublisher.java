package com.ureca.snac.outbox.service;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.config.AggregateExchangeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 메시지 발행 공통 컴포넌트
 * OutboxPublisher, AsyncOutboxPublisher에서 위임하여 사용
 * AggregateType/EventType 변환 + 메시지 발행 + 헤더 설정
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessagePublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * RabbitMQ로 메시지 발행
     *
     * @param eventId           이벤트 고유 식별자 (Consumer 측 멱등성 검증용)
     * @param aggregateTypeName Aggregate 타입명
     * @param eventTypeName     이벤트 타입명
     * @param aggregateId       Aggregate ID
     * @param payload           JSON 페이로드
     */
    public void publish(
            String eventId,
            String aggregateTypeName,
            String eventTypeName,
            Long aggregateId,
            String payload
    ) {
        AggregateType aggregateType = AggregateType.from(aggregateTypeName);
        EventType eventType = EventType.from(eventTypeName);
        String exchange = AggregateExchangeMapper.getExchange(aggregateType);

        rabbitTemplate.convertAndSend(
                exchange,
                eventType.getRoutingKey(),
                payload,
                message -> {
                    message.getMessageProperties().setHeader("eventId", eventId);
                    message.getMessageProperties().setHeader("eventType", eventTypeName);
                    message.getMessageProperties().setHeader("aggregateId", aggregateId);
                    return message;
                }
        );

        log.debug("[OutboxMessagePublisher] 발행 완료. exchange: {}, routingKey: {}, eventId: {}, aggregateId: {}",
                exchange, eventType.getRoutingKey(), eventId, aggregateId);
    }
}
