package com.ureca.snac.config;

import com.ureca.snac.common.event.EventType;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Outbox 패턴 기반 이벤트 체이닝 전용 RabbitMQ 설정
 * Listener 재시도 정책
 * JSON 파싱 실패, 회원 없음 등에서는 즉시 DLQ
 * 나머지는 3회 재시도 (1초, 2초, 4초 간격)
 * <p>
 * 메인 큐 -> DLX -> DLQ
 * 모니터링 : DlqMonitor.java 가 1분마다 감시 -> Slack 알림
 */
@Configuration
@EnableRabbit
public class OutboxRabbitMQConfig {

    // --------------------- Listener Factory --------------------------

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            RetryOperationsInterceptor retryInterceptor
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);

        // 1. 재시도 인터셉터
        factory.setAdviceChain(retryInterceptor);

        // 2. 큐에 다시 넣지 말고 -> DLQ로
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    // Retry + Recover
    @Bean
    public RetryOperationsInterceptor retryInterceptor(
            @Value("${rabbitmq.listener.retry.max-attempts}") int maxAttempts,
            @Value("${rabbitmq.listener.retry.initial-interval}") long initialInterval,
            @Value("${rabbitmq.listener.retry.multiplier}") double multiplier,
            @Value("${rabbitmq.listener.retry.max-interval}") long maxInterval
    ) {
        return RetryInterceptorBuilder.stateless()
                .retryOperations(createRetryTemplate(
                                maxAttempts, initialInterval, multiplier, maxInterval
                        )
                )
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }

    /**
     * Retry 정책 생성
     * <p>
     * JSON 파싱 실패, 회원 없음 등 AmqpRejectAndDontRequeueException 예외면 재시도 안 함
     * <p>
     * 나머지는 재시도 함 (최대 3회 - 지수 간격 : 1초, 2초, 4초)
     * <p>
     * 3번 실패 후: DLQ로 전송
     *
     * @param maxAttempts     최대 재시도 횟수
     * @param initialInterval 처음 인터벌 시간
     * @param multiplier      지수 곱 증가
     * @param maxInterval     최대 인터벌 시간
     * @return RetryTemplate
     */
    private RetryTemplate createRetryTemplate(
            int maxAttempts,
            long initialInterval,
            double multiplier,
            long maxInterval
    ) {
        RetryTemplate retryTemplate = new RetryTemplate();

        // 1. 예외가 키로 해서 Map으로 매핑
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();

        // 재시도 안 함 (즉시 DLQ)
        retryableExceptions.put(AmqpRejectAndDontRequeueException.class, false);

        // 나머지는 재시도
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(maxAttempts, retryableExceptions, true);
        retryTemplate.setRetryPolicy(retryPolicy);

        // 2. 지수 증가 간격
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(initialInterval);
        backOffPolicy.setMultiplier(multiplier);
        backOffPolicy.setMaxInterval(maxInterval);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        return retryTemplate;
    }

    // --------------------- Exchange --------------------------

    @Bean
    public DirectExchange memberExchange() {
        return new DirectExchange(RabbitMQQueue.MEMBER_EXCHANGE);
    }

    @Bean
    public DirectExchange walletExchange() {
        return new DirectExchange(RabbitMQQueue.WALLET_EXCHANGE);
    }

    @Bean
    public DirectExchange outboxDeadLetterExchange() {
        return new DirectExchange(RabbitMQQueue.OUTBOX_DLX);
    }

    // --------------------- Member Domain --------------------------

    // 회원가입 이벤트 큐 실패 시 dlx.outbox로 전송 (dlx.member.joined 꼬리표 )
    @Bean
    public Queue memberJoinedQueue() {
        return QueueBuilder.durable(RabbitMQQueue.MEMBER_JOINED_QUEUE)
                .withArgument("x-dead-letter-exchange", RabbitMQQueue.OUTBOX_DLX)
                .withArgument("x-dead-letter-routing-key", RabbitMQQueue.MEMBER_JOINED_DLX_ROUTING_KEY)
                .build();
    }

    // 회원가입 DLQ
    @Bean
    public Queue memberJoinedDlq() {
        return new Queue(RabbitMQQueue.MEMBER_JOINED_DLQ, true);
    }

    // 회원가입 큐 -> member.exchange 바인딩
    @Bean
    public Binding memberJoinedBinding() {
        return BindingBuilder
                .bind(memberJoinedQueue())
                .to(memberExchange())
                .with(EventType.MEMBER_JOIN.getRoutingKey());
    }

    // DLQ → DLX 바인딩
    @Bean
    public Binding memberJoinedDlqBinding() {
        return BindingBuilder
                .bind(memberJoinedDlq())
                .to(outboxDeadLetterExchange())
                .with(RabbitMQQueue.MEMBER_JOINED_DLX_ROUTING_KEY);
    }

    // --------------------- Wallet Domain --------------------------

    // 지갑 생성 이벤트 큐 실패 시 dlx.outbox로 전송 (dlx.wallet.created 꼬리표)
    @Bean
    public Queue walletCreatedQueue() {
        return QueueBuilder.durable(RabbitMQQueue.WALLET_CREATED_QUEUE)
                .withArgument("x-dead-letter-exchange", RabbitMQQueue.OUTBOX_DLX)
                .withArgument("x-dead-letter-routing-key", RabbitMQQueue.WALLET_CREATED_DLX_ROUTING_KEY)
                .build();
    }

    // 지갑 생성 DLQ
    @Bean
    public Queue walletCreatedDlq() {
        return new Queue(RabbitMQQueue.WALLET_CREATED_DLQ, true);
    }

    // 지갑 생성 큐 -> wallet.exchange 바인딩
    @Bean
    public Binding walletCreatedBinding() {
        return BindingBuilder
                .bind(walletCreatedQueue())
                .to(walletExchange())
                .with(EventType.WALLET_CREATED.getRoutingKey());
    }

    // DLQ → DLX 바인딩
    @Bean
    public Binding walletCreatedDlqBinding() {
        return BindingBuilder
                .bind(walletCreatedDlq())
                .to(outboxDeadLetterExchange())
                .with(RabbitMQQueue.WALLET_CREATED_DLX_ROUTING_KEY);
    }
}