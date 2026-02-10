package com.ureca.snac.outbox.service;

import com.ureca.snac.outbox.event.OutboxScheduledEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
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

    private AsyncOutboxPublisher asyncOutboxPublisher;
    private SimpleMeterRegistry meterRegistry;

    @Mock
    private OutboxStatusUpdater statusUpdater;

    @Mock
    private OutboxMessagePublisher messagePublisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        asyncOutboxPublisher = new AsyncOutboxPublisher(
                messagePublisher, statusUpdater, meterRegistry
        );
    }

    @Test
    @DisplayName("Hybrid Push : RabbitMQ 발행 성공 -> PUBLISHED 상태 업데이트")
    void pushImmediately_Success() {
        // given : 회원가입 이벤트
        OutboxScheduledEvent event = new OutboxScheduledEvent(
                1L,
                "event-uuid-001",
                "MemberJoinEvent",
                "MEMBER",
                100L,
                "{\"memberId\":100}"
        );

        // when : 바로 발행
        asyncOutboxPublisher.pushImmediately(event);

        // then : 메시지 발행 (eventId 포함)
        verify(messagePublisher, times(1))
                .publish(
                        eq("event-uuid-001"),
                        eq("MEMBER"),
                        eq("MemberJoinEvent"),
                        eq(100L),
                        eq("{\"memberId\":100}")
                );

        // PUBLISHED 상태 업데이트 (원자성)
        verify(statusUpdater, times(1))
                .markAsPublished(eq(1L));

        // 실패 처리 안 됨
        verify(statusUpdater, never())
                .markAsFailed(anyLong());

        // 메트릭 검증
        assertThat(meterRegistry.get("outbox_events_published_total")
                .tag("result", "success").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Hybrid Push : RabbitMQ 발행 실패시 SEND_FAIL 상태 업데이트 스케줄러 즉시 재시도")
    void pushImmediately_Fail_SchedulerRetry() {
        // given : 지갑 생성 이벤트
        OutboxScheduledEvent event = new OutboxScheduledEvent(
                2L,
                "event-uuid-002",
                "WalletCreatedEvent",
                "WALLET",
                200L,
                "{\"walletId\":200}"
        );

        // 메시지 발행 실패 (네트워크 장애)
        doThrow(new RuntimeException("RabbitMQ 연결 실패"))
                .when(messagePublisher)
                .publish(anyString(), anyString(), anyString(), anyLong(), anyString());

        // when : 즉시 발행 시도
        asyncOutboxPublisher.pushImmediately(event);

        // then : SEND_FAIL 상태 업데이트
        verify(statusUpdater, times(1))
                .markAsFailed(eq(2L));

        // 성공 처리 안 됨
        verify(statusUpdater, never())
                .markAsPublished(anyLong());

        // 메트릭 검증
        assertThat(meterRegistry.get("outbox_events_published_total")
                .tag("result", "fail").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Hybrid Push : eventId 전달 검증")
    void pushImmediately_EventIdPropagation() {
        // given : 회원가입 이벤트
        OutboxScheduledEvent memberEvent = new OutboxScheduledEvent(
                3L,
                "unique-event-id-123",
                "MemberJoinEvent",
                "MEMBER",
                300L,
                "{}"
        );

        // when
        asyncOutboxPublisher.pushImmediately(memberEvent);

        // then : eventId가 정확히 전달됨
        verify(messagePublisher).publish(
                eq("unique-event-id-123"),
                eq("MEMBER"),
                eq("MemberJoinEvent"),
                eq(300L),
                eq("{}")
        );

        // given : 지갑 생성 이벤트
        OutboxScheduledEvent walletEvent = new OutboxScheduledEvent(
                4L,
                "wallet-event-id-456",
                "WalletCreatedEvent",
                "WALLET",
                400L,
                "{}"
        );

        // when
        asyncOutboxPublisher.pushImmediately(walletEvent);

        // then : eventId가 정확히 전달됨
        verify(messagePublisher).publish(
                eq("wallet-event-id-456"),
                eq("WALLET"),
                eq("WalletCreatedEvent"),
                eq(400L),
                eq("{}")
        );
    }

    @Test
    @DisplayName("Hybrid Push: payload 변조 없이 전달")
    void pushImmediately_PayloadIntegrity() {
        // given : 복잡한 payload
        String payload = "{\"memberId\":999,\"email\":\"test@example.com\",\"name\":\"홍길동\"}";

        OutboxScheduledEvent event = new OutboxScheduledEvent(
                5L,
                "event-uuid-005",
                "MemberJoinEvent",
                "MEMBER",
                999L,
                payload
        );

        // when
        asyncOutboxPublisher.pushImmediately(event);

        // then : payload 원본 그대로
        verify(messagePublisher).publish(
                anyString(),
                anyString(),
                anyString(),
                anyLong(),
                eq(payload)
        );
    }
}