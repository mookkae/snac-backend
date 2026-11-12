package com.ureca.snac.outbox.service;

import com.rabbitmq.client.ShutdownSignalException;
import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.outbox.exception.BrokerUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.doAnswer;
import static org.mockito.BDDMockito.doThrow;

@ExtendWith(MockitoExtension.class)
@DisplayName("OutboxMessagePublisher 단위 테스트")
class OutboxMessagePublisherTest {

    private OutboxMessagePublisher publisher;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void setUp() {
        publisher = new OutboxMessagePublisher(rabbitTemplate);
    }

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
                    .isNotInstanceOf(BrokerUnavailableException.class)
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

    // 브로커가 멈춘 건 메시지 탓이 아니라서 호출자가 재시도 횟수를 쓰지 않도록 따로 알린다
    @Nested
    @DisplayName("publish - 브로커 연결 장애 판별")
    class BrokerUnavailableTest {

        @Test
        @DisplayName("연결 거부 -> BrokerUnavailableException")
        void publish_ConnectionRefused_ThrowsBrokerUnavailable() {
            // given
            givenSendThrows(new AmqpConnectException(new ConnectException("Connection refused")));

            // when & then
            assertThatThrownBy(() -> publisher.publish(EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, AGGREGATE_ID, PAYLOAD))
                    .isInstanceOf(BrokerUnavailableException.class);
        }

        // 브로커 앱이 멈췄거나 재시작 중이면 연결이 닫힌다. Spring은 바깥 IOException만 보고 AmqpIOException으로 바꾼다
        @Test
        @DisplayName("연결 단위 종료 -> BrokerUnavailableException")
        void publish_ConnectionShutdown_ThrowsBrokerUnavailable() {
            // given
            ShutdownSignalException connectionClosed = new ShutdownSignalException(true, false, null, null);
            givenSendThrows(new AmqpIOException(new IOException(connectionClosed)));

            // when & then
            assertThatThrownBy(() -> publisher.publish(EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, AGGREGATE_ID, PAYLOAD))
                    .isInstanceOf(BrokerUnavailableException.class);
        }

        @Test
        @DisplayName("소켓 리셋 -> BrokerUnavailableException")
        void publish_SocketReset_ThrowsBrokerUnavailable() {
            // given
            givenSendThrows(new AmqpIOException(new IOException(new SocketException("Connection reset"))));

            // when & then
            assertThatThrownBy(() -> publisher.publish(EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, AGGREGATE_ID, PAYLOAD))
                    .isInstanceOf(BrokerUnavailableException.class);
        }

        // 없는 익스체인지 등 채널만 닫힌 오류는 메시지나 설정 문제다. 호출자가 횟수를 써서 결국 포기하고 알려야 한다
        @Test
        @DisplayName("채널 단위 오류 -> 원래 예외 그대로")
        void publish_ChannelError_KeepsOriginalException() {
            // given
            ShutdownSignalException channelClosed = new ShutdownSignalException(false, false, null, null);
            givenSendThrows(new AmqpIOException(new IOException(channelClosed)));

            // when & then
            assertThatThrownBy(() -> publisher.publish(EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, AGGREGATE_ID, PAYLOAD))
                    .isInstanceOf(AmqpIOException.class)
                    .isNotInstanceOf(BrokerUnavailableException.class);
        }

        // Spring은 바깥이 종료 신호면 채널 오류도 AmqpConnectException으로 감싼다. 타입보다 종료 범위가 우선이다
        @Test
        @DisplayName("연결 예외로 감싼 채널 오류 -> 원래 예외 그대로")
        void publish_ChannelErrorWrappedAsConnect_KeepsOriginalException() {
            // given
            ShutdownSignalException channelClosed = new ShutdownSignalException(false, false, null, null);
            givenSendThrows(new AmqpConnectException(channelClosed));

            // when & then
            assertThatThrownBy(() -> publisher.publish(EVENT_ID, AGGREGATE_TYPE, EVENT_TYPE, AGGREGATE_ID, PAYLOAD))
                    .isInstanceOf(AmqpConnectException.class)
                    .isNotInstanceOf(BrokerUnavailableException.class);
        }
    }

    private void givenSendThrows(RuntimeException e) {
        doThrow(e).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(), any(), any(CorrelationData.class));
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
