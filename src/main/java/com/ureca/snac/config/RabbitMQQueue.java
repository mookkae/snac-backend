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

    // Payment Domain (보상 트랜잭션)
    public static final String PAYMENT_CANCEL_COMPENSATE_QUEUE = "payment.cancel.compensate.queue";
    public static final String PAYMENT_CANCEL_COMPENSATE_DLQ = "payment.cancel.compensate.dlq";

    // Exchange
    public static final String MEMBER_EXCHANGE = "member.exchange";
    public static final String WALLET_EXCHANGE = "wallet.exchange";
    public static final String OUTBOX_DLX = "dlx.outbox";
    public static final String PAYMENT_EXCHANGE = "payment.exchange";

    // DLX Routing Key
    public static final String MEMBER_JOINED_DLX_ROUTING_KEY = "dlx.member.joined";
    public static final String WALLET_CREATED_DLX_ROUTING_KEY = "dlx.wallet.created";
    public static final String PAYMENT_CANCEL_COMPENSATE_DLX_ROUTING_KEY = "dlx.payment.cancel.compensate";

    private RabbitMQQueue() {
        // 인스턴스화 방지
    }
}