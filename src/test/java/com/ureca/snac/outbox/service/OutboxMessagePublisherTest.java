package com.ureca.snac.outbox.service;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.doAnswer;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxMessagePublisher 단위 테스트")
class OutboxMessagePublisherTest {

    @InjectMocks
    private OutboxMessagePublisher publisher;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private static final String EVENT_ID = "test-event-id";
    private static final String AGGREGATE_TYPE = AggregateType.MEMBER.getTypeName();
    private static final String EVENT_TYPE = EventType.MEMBER_JOIN.getTypeName();
    private static final Long AGGREGATE_ID = 1L;
    private static final String PAYLOAD = "{\"id\":1}";

    @Nested
    @DisplayName("publish - Publisher Confirms")
    class PublishTest {

        @Test
        @DisplayName("성공 : 브로커 ACK 수신")
        void publish_BrokerAck_NoException() {
            // given
            givenBrokerConfirm(true, null);

            // when & then
            assertThatCode(() -> publisher.publish(EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, AGGREGATE_ID, PAYLOAD))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("실패 : 브로커 NACK -> AmqpException")
        void publish_BrokerNack_ThrowsAmqpException() {
            // given
            givenBrokerConfirm(false, "queue full");

            // when & then
            assertThatThrownBy(() -> publisher.publish(EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, AGGREGATE_ID, PAYLOAD))
                    .isInstanceOf(AmqpException.class)
                    .hasMessageContaining("NACK");
        }

        @Test
        @Timeout(10)
        @DisplayName("실패 : Confirm 타임아웃 -> AmqpException")
        void publish_ConfirmTimeout_ThrowsAmqpException() {
            // given: future 미완료 -> CONFIRM_TIMEOUT_SECONDS 후 TimeoutException
            givenFutureNeverCompletes();

            // when & then
            assertThatThrownBy(() -> publisher.publish(EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, AGGREGATE_ID, PAYLOAD))
                    .isInstanceOf(AmqpException.class)
                    .hasMessageContaining("타임아웃");
        }

        @Test
        @DisplayName("실패 : 스레드 인터럽트 -> AmqpException")
        void publish_ThreadInterrupted_ThrowsAmqpException() {
            // given
            givenThreadInterrupted();

            // when & then
            assertThatThrownBy(() -> publisher.publish(EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, AGGREGATE_ID, PAYLOAD))
                    .isInstanceOf(AmqpException.class)
                    .hasMessageContaining("인터럽트");
        }

        @Test
        @DisplayName("실패 : Future 실행 오류 -> AmqpException")
        void publish_ExecutionException_ThrowsAmqpException() {
            // given
            givenFutureCompletesExceptionally();

            // when & then
            assertThatThrownBy(() -> publisher.publish(EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, AGGREGATE_ID, PAYLOAD))
                    .isInstanceOf(AmqpException.class)
                    .hasMessageContaining("실행 오류");
        }
    }

    private void givenBrokerConfirm(boolean ack, String reason) {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(4);
            cd.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }

    private void givenFutureNeverCompletes() {
        doAnswer(invocation -> null)
                .when(rabbitTemplate).convertAndSend(
                        anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }

    private void givenThreadInterrupted() {
        doAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }

    private void givenFutureCompletesExceptionally() {
        doAnswer(invocation -> {
            CorrelationData cd = invocation.getArgument(4);
            cd.getFuture().completeExceptionally(new RuntimeException("connection lost"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
}
