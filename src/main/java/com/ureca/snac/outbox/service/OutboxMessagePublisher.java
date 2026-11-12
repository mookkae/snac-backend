package com.ureca.snac.outbox.service;

import com.rabbitmq.client.ShutdownSignalException;
import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.config.AggregateExchangeMapper;
import com.ureca.snac.outbox.exception.BrokerUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.net.SocketException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 메시지 발행 공통 컴포넌트
 * OutboxPollingScheduler, AsyncOutboxPublisher에서 위임하여 사용
 * AggregateType/EventType 변환 + 메시지 발행 + 헤더 설정
 * <p>
 * Publisher Confirms:
 * rabbitTemplate.convertAndSend() 후 브로커의 ACK/NACK을 대기하여
 * 메시지가 실제로 브로커에 도달했는지 확인한다.
 * 확인 실패 시 예외를 던져 호출자가 SEND_FAIL로 처리하도록 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessagePublisher {

    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;

    /**
     * RabbitMQ로 메시지 발행 + 브로커 수신 확인 (Publisher Confirms)
     *
     * @param eventId           이벤트 고유 식별자 (Consumer 측 멱등성 검증용)
     * @param aggregateTypeName Aggregate 타입명
     * @param eventTypeName     이벤트 타입명
     * @param aggregateId       Aggregate ID
     * @param payload           JSON 페이로드
     * @throws BrokerUnavailableException 브로커 연결 장애 시 (메시지 탓이 아님)
     * @throws AmqpException               브로커 NACK, 타임아웃 등 그 밖의 실패 시
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

        CorrelationData correlationData = new CorrelationData(eventId);

        try {
            send(exchange, eventType, eventId, eventTypeName, aggregateId, payload, correlationData);
        } catch (AmqpException e) {
            if (isBrokerConnectionFailure(e)) {
                throw new BrokerUnavailableException(e);
            }
            throw e;
        }

        log.debug("[OutboxMessagePublisher] 발행 + 브로커 확인 완료. exchange: {}, routingKey: {}, eventId: {}, aggregateId: {}",
                exchange, eventType.getRoutingKey(), eventId, aggregateId);
    }

    private void send(String exchange, EventType eventType, String eventId, String eventTypeName,
                      Long aggregateId, String payload, CorrelationData correlationData) {
        rabbitTemplate.convertAndSend(
                exchange,
                eventType.getRoutingKey(),
                payload,
                message -> {
                    message.getMessageProperties().setHeader("eventId", eventId);
                    message.getMessageProperties().setHeader("eventType", eventTypeName);
                    message.getMessageProperties().setHeader("aggregateId", aggregateId);
                    return message;
                },
                correlationData
        );

        // 브로커 수신 확인 대기 (Publisher Confirms)
        waitForConfirm(correlationData, eventId);
    }

    /**
     * 연결 거부뿐 아니라 브로커가 재시작 중이라 연결을 끊는 경우도 연결 장애로 본다.
     * Spring은 바깥 IOException만 보고 AmqpIOException으로 바꾸고, 바깥이 종료 신호면 채널 오류도 AmqpConnectException으로 감싼다.
     * 그래서 타입보다 원인 사슬의 종료 범위를 먼저 본다. 채널만 닫힌 오류(없는 익스체인지 등)는 메시지나 설정 문제다.
     */
    private boolean isBrokerConnectionFailure(AmqpException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ShutdownSignalException signal) {
                return signal.isHardError();
            }
            if (cause instanceof SocketException) {
                return true;
            }
        }
        return e instanceof AmqpConnectException;
    }

    // CorrelationData 기반으로 브로커 확인 대기 (5초 타임아웃)
    private void waitForConfirm(CorrelationData correlationData, String eventId) {
        try {
            CorrelationData.Confirm confirm = correlationData.getFuture()
                    .get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!confirm.isAck()) {
                throw new AmqpException(
                        "브로커 NACK. eventId: " + eventId + ", reason: " + confirm.getReason());
            }
        } catch (TimeoutException e) {
            throw new AmqpException("Publisher Confirm 타임아웃 (" + CONFIRM_TIMEOUT_SECONDS + "s). eventId: " + eventId, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("Publisher Confirm 인터럽트. eventId: " + eventId, e);
        } catch (ExecutionException e) {
            throw new AmqpException("Publisher Confirm 실행 오류. eventId: " + eventId, e.getCause());
        }
    }
}
