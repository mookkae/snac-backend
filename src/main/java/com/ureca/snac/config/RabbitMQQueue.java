package com.ureca.snac.config;

/**
 * RabbitMQ 큐 이름 및 인프라 상수 관리
 */
public final class RabbitMQQueue {

    // Member Domain
    public static final String MEMBER_JOINED_QUEUE = "member.joined.queue";
    public static final String MEMBER_JOINED_DLQ = "member.joined.dlq";

    // Wallet Domain
    public static final String WALLET_CREATED_QUEUE = "wallet.created.queue";
    public static final String WALLET_CREATED_DLQ = "wallet.created.dlq";

    // Exchange
    public static final String MEMBER_EXCHANGE = "member.exchange";
    public static final String WALLET_EXCHANGE = "wallet.exchange";
    public static final String OUTBOX_DLX = "dlx.outbox";

    // DLX Routing Key
    public static final String MEMBER_JOINED_DLX_ROUTING_KEY = "dlx.member.joined";
    public static final String WALLET_CREATED_DLX_ROUTING_KEY = "dlx.wallet.created";

    private RabbitMQQueue() {
        // 인스턴스화 방지
    }
}