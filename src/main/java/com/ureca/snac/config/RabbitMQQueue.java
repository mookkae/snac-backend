package com.ureca.snac.config;

/**
 * RabbitMQ 큐 이름 중앙 관리
 *
 * @RabbitListener는 컴파일 타임 상수만 허용하므로 static final 사용
 * <p>
 * 네이밍 규칙:
 * - 일반 큐: {domain}.{event}.queue
 * - DLQ: dlq.{domain}.{event}.queue
 */
public final class RabbitMQQueue {

    // Member Domain
    public static final String MEMBER_JOINED_QUEUE = "member.joined.queue";
    public static final String MEMBER_JOINED_DLQ = "dlq.member.joined.queue";

    // Wallet Domain
    public static final String WALLET_CREATED_QUEUE = "wallet.created.queue";
    public static final String WALLET_CREATED_DLQ = "dlq.wallet.created.queue";

    private RabbitMQQueue() {
        // 인스턴스화 방지
    }
}