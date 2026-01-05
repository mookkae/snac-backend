package com.ureca.snac.outbox.service;

import com.ureca.snac.outbox.event.OutboxScheduledEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * - @Async + AFTER_COMMIT 조합 비동기 즉시 발행
 * - 성공 시 PUBLISHED 상태
 * - 실패 시 SEND_FAIL 상태 (스케줄러 재시도)
 * - 실패 처리 전략 (즉시 재시도 vs 스케줄러 백업)
 */
@ExtendWith(MockitoExtension.class)
class AsyncOutboxPublisherTest {

    @InjectMocks
    private AsyncOutboxPublisher asyncOutboxPublisher;

    @Mock
    private OutboxStatusUpdater statusUpdater;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    @DisplayName("Hybrid Push : RabbitMQ 발행 성공 -> PUBLISHED 상태 업데이트")
    void pushImmediately_Success() {
        // given : 회원가입 이벤트
        OutboxScheduledEvent event = new OutboxScheduledEvent(
                1L,
                "MemberJoinEvent",
                "MEMBER",
                100L,
                "{\"memberId\":100}"
        );

        // when : 바로 발행
        asyncOutboxPublisher.pushImmediately(event);

        // then : RabbitMQ 발행 (member.exchange -> member.joined)
        verify(rabbitTemplate, times(1))
                .convertAndSend(
                        eq("member.exchange"),
                        eq("member.joined"),
                        eq("{\"memberId\":100}"),
                        any(MessagePostProcessor.class)
                );

        // PUBLISHED 상태 업데이트 (원자성)
        verify(statusUpdater, times(1))
                .markAsPublished(eq(1L));

        // 실패 처리 안 됨
        verify(statusUpdater, never())
                .markAsFailed(anyLong());
    }

    @Test
    @DisplayName("Hybrid Push : RabbitMQ 발행 실패시 SEND_FAIL 상태 업데이트" +
            "스케줄러 즉시 재시도")
    void pushImmediately_Fail_SchedulerRetry() {
        // given : 지갑 생성 이벤트
        OutboxScheduledEvent event = new OutboxScheduledEvent(
                2L,
                "WalletCreatedEvent",
                "WALLET",
                200L,
                "{\"walletId\":200}"
        );

        // RabbitMQ 발행 실패 (네트워크 장애)
        doThrow(new RuntimeException("RabbitMQ 연결 실패"))
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(MessagePostProcessor.class)
                );

        // when : 즉시 발행 시도
        asyncOutboxPublisher.pushImmediately(event);

        // then : SEND_FAIL 상태 업데이트
        verify(statusUpdater, times(1))
                .markAsFailed(eq(2L));

        // 성공 처리 안 됨
        verify(statusUpdater, never())
                .markAsPublished(anyLong());
    }

    @Test
    @DisplayName("Hybrid Push : Exchange와 RoutingKey 매핑 검증")
    void pushImmediately_Correcting() {
        // given : 회원가입 이벤트
        OutboxScheduledEvent memberEvent = new OutboxScheduledEvent(
                3L,
                "MemberJoinEvent",
                "MEMBER",
                300L,
                "{}"
        );

        // when
        asyncOutboxPublisher.pushImmediately(memberEvent);

        // then : member.exchange + member.joined
        verify(rabbitTemplate).convertAndSend(
                eq("member.exchange"),
                eq("member.joined"),
                anyString(),
                any(MessagePostProcessor.class)
        );

        // given : 지갑 생성 이벤트
        OutboxScheduledEvent walletEvent = new OutboxScheduledEvent(
                4L,
                "WalletCreatedEvent",
                "WALLET",
                400L,
                "{}"
        );

        // when
        asyncOutboxPublisher.pushImmediately(walletEvent);

        // then : wallet.exchange + wallet.created
        verify(rabbitTemplate).convertAndSend(
                eq("wallet.exchange"),
                eq("wallet.created"),
                anyString(),
                any(MessagePostProcessor.class)
        );
    }

    @Test
    @DisplayName("Hybrid Push: payload 변조 없이 전달")
    void pushImmediately_PayloadIntegrity() {
        // given : 복잡한 payload
        String payload = "{\"memberId\":999,\"email\":\"test@example.com\",\"name\":\"홍길동\"}";

        OutboxScheduledEvent event = new OutboxScheduledEvent(
                5L,
                "MemberJoinEvent",
                "MEMBER",
                999L,
                payload
        );

        // when
        asyncOutboxPublisher.pushImmediately(event);

        // then : payload 원본 그대로
        verify(rabbitTemplate).convertAndSend(
                anyString(),
                anyString(),
                eq(payload),
                any(MessagePostProcessor.class)
        );
    }
}