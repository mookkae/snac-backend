package com.ureca.snac.outbox.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutboxMessagePublisherConnectionTest {

    // 폴러가 브로커 장애를 이 타입으로 판단한다. 라이브러리가 바뀌면 여기서 먼저 깨져야 한다
    @Test
    @DisplayName("꺼진 브로커로 발행하면 AmqpConnectException이다")
    void throwsConnectExceptionWhenBrokerIsDown() {
        // given : 아무도 듣지 않는 포트
        CachingConnectionFactory connectionFactory = new CachingConnectionFactory("localhost", 1);
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        OutboxMessagePublisher publisher = new OutboxMessagePublisher(new RabbitTemplate(connectionFactory));

        // when & then
        assertThatThrownBy(() -> publisher.publish("event-1", "MEMBER", "MemberJoinEvent", 1L, "{}"))
                .isInstanceOf(AmqpConnectException.class);

        connectionFactory.destroy();
    }
}
