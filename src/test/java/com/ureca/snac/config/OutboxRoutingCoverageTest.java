package com.ureca.snac.config;

import com.ureca.snac.common.event.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 모든 EventType에 큐로 가는 Binding이 있는지 빌드 시점에 검증한다.
 * <p>
 * 매칭되는 Binding이 없으면 브로커는 메시지를 반송(return)한 뒤 ACK를 보내므로
 * Publisher Confirms만으로는 라우팅 누락을 잡지 못한다.
 * EventType 상수가 추가되면 @EnumSource로 케이스가 자동 추가되어 Binding 누락 시 실패한다.
 */
@DisplayName("Outbox 이벤트 라우팅 커버리지 테스트")
class OutboxRoutingCoverageTest {

    // 브로커 연결 없이 OutboxRabbitMQConfig가 선언한 Exchange/Queue/Binding 빈만 로드
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OutboxRabbitMQConfig.class)
            .withBean(ConnectionFactory.class, () -> mock(ConnectionFactory.class))
            .withBean(Jackson2JsonMessageConverter.class)
            .withPropertyValues(
                    "rabbitmq.dlq.ttl-ms=1000",
                    "rabbitmq.listener.retry.max-attempts=3",
                    "rabbitmq.listener.retry.initial-interval=1000",
                    "rabbitmq.listener.retry.multiplier=2.0",
                    "rabbitmq.listener.retry.max-interval=10000"
            );

    @ParameterizedTest(name = "{0}")
    @EnumSource(EventType.class)
    @DisplayName("성공 : 모든 EventType은 선언된 Exchange에서 선언된 Queue로 가는 Binding을 가진다")
    void everyEventType_HasBindingToDeclaredQueue(EventType eventType) {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            // given
            Set<String> exchanges = beanNames(context, Exchange.class, Exchange::getName);
            Set<String> queues = beanNames(context, Queue.class, Queue::getName);

            // when & then
            assertThat(context.getBeansOfType(Binding.class).values())
                    .filteredOn(binding -> binding.getRoutingKey().equals(eventType.getRoutingKey()))
                    .as("routingKey '%s'에 대한 Binding이 없음", eventType.getRoutingKey())
                    .isNotEmpty()
                    .allSatisfy(binding -> {
                        assertThat(binding.getExchange()).isIn(exchanges);
                        assertThat(binding.getDestination()).isIn(queues);
                    });
        });
    }

    private <T> Set<String> beanNames(ApplicationContext ac, Class<T> type, Function<T, String> nameOf) {
        return ac.getBeansOfType(type).values().stream()
                .map(nameOf)
                .collect(Collectors.toSet());
    }
}
