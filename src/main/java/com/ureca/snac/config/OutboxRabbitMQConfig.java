package com.ureca.snac.config;

import com.ureca.snac.common.event.EventType;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Outbox 패턴 기반 이벤트 체이닝 전용 RabbitMQ 설정
@Configuration
@EnableRabbit
public class OutboxRabbitMQConfig {

    /* ------------------- Exchange ------------------- */

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

    /* ------------------- Member Domain ------------------- */

    /**
     * 회원가입 이벤트 큐
     * DLX 설정: 실패 시 dlx.outbox로 전송 (꼬리표: dlx.member.joined)
     */
    @Bean
    public Queue memberJoinedQueue() {
        return QueueBuilder.durable(RabbitMQQueue.MEMBER_JOINED_QUEUE)
                .withArgument("x-dead-letter-exchange", RabbitMQQueue.OUTBOX_DLX)  // DLX 지정
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

    /* ------------------- Wallet Domain ------------------- */

    /**
     * 지갑 생성 이벤트 큐
     * DLX 설정: 실패 시 dlx.outbox로 전송 (꼬리표: dlx.wallet.created)
     */
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