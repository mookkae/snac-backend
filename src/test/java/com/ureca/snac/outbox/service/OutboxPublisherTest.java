package com.ureca.snac.outbox.service;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.fixture.OutboxFixture;
import com.ureca.snac.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * OutboxPublisher 스케줄러 테스트
 * - SEND_FAIL 이벤트 재발행
 * - 오래된 INIT 이벤트 재발행 (서버 장애 복구)
 * - 배치 처리 (100개씩)
 * - 실패 시 상태 업데이트
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @InjectMocks
    private OutboxPublisher outboxPublisher;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private OutboxStatusUpdater outboxStatusUpdater;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    @DisplayName("스케줄러 : SEND_FAIL 이벤트 재발행")
    void publishPendingEvents_SendFail() {
        // given : SEND_FAIL 이벤트
        Outbox outbox1 = OutboxFixture.memberJoinSendFail(1L);

        Outbox outbox2 = OutboxFixture.builder()
                .eventType(EventType.WALLET_CREATED)
                .aggregateType(AggregateType.WALLET)
                .aggregateId(2L)
                .status(OutboxStatus.SEND_FAIL)
                .withId(2L)
                .build();

        given(outboxRepository.findPendingEvents(
                eq(OutboxStatus.SEND_FAIL),
                eq(OutboxStatus.INIT),
                any(LocalDateTime.class),
                eq(PageRequest.of(0, 100))
        )).willReturn(List.of(outbox1, outbox2));

        // when : 스케줄러 실행
        outboxPublisher.publishPendingEvents();

        // then : RabbitMQ 발행 2회
        verify(rabbitTemplate, times(1))
                .convertAndSend(
                        eq("member.exchange"),
                        eq("member.joined"),
                        anyString(),
                        any(MessagePostProcessor.class)
                );

        verify(rabbitTemplate, times(1))
                .convertAndSend(
                        eq("wallet.exchange"),
                        eq("wallet.created"),
                        anyString(),
                        any(MessagePostProcessor.class)
                );

        // 상태 업데이트 2회 (PUBLISHED)
        verify(outboxStatusUpdater, times(2))
                .markAsPublished(anyLong());
    }

    @Test
    @DisplayName("스케줄러 : 실패 시 SEND_FAIL 업데이트")
    void publishPendingEvents_Fail() {
        // given: SEND_FAIL 이벤트
        Outbox outbox = OutboxFixture.memberJoinSendFail(1L);

        given(outboxRepository.findPendingEvents(
                any(), any(), any(), any()
        )).willReturn(List.of(outbox));

        // RabbitMQ 발행 실패
        doThrow(new RuntimeException("RabbitMQ 연결 실패"))
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(MessagePostProcessor.class)
                );

        // when : 스케줄러 실행
        outboxPublisher.publishPendingEvents();

        // then : 실패 상태 업데이트
        verify(outboxStatusUpdater, times(1))
                .markAsFailed(anyLong());

        // PUBLISHED 업데이트 안 됨
        verify(outboxStatusUpdater, never())
                .markAsPublished(anyLong());
    }

    @Test
    @DisplayName("스케줄러 : 배치 크기 제한 (100개)")
    void publishPendingEvents_BatchLimit() {
        // given : 이벤트 없음
        given(outboxRepository.findPendingEvents(
                any(), any(), any(), any()
        )).willReturn(List.of());

        // when : 스케줄러 실행
        outboxPublisher.publishPendingEvents();

        // then : PageRequest 100개로 호출
        verify(outboxRepository, times(1))
                .findPendingEvents(
                        eq(OutboxStatus.SEND_FAIL),
                        eq(OutboxStatus.INIT),
                        any(LocalDateTime.class),
                        eq(PageRequest.of(0, 100))
                );
    }

    @Test
    @DisplayName("스케줄러 : 5분 이전 INIT 이벤트 포함 조회")
    void publishPendingEvents_IncludeStalledInit() {
        // given
        given(outboxRepository.findPendingEvents(
                any(), any(), any(), any()
        )).willReturn(List.of());

        // when
        outboxPublisher.publishPendingEvents();

        // then : 5분 이전 기준으로 조회
        verify(outboxRepository, times(1))
                .findPendingEvents(
                        eq(OutboxStatus.SEND_FAIL),
                        eq(OutboxStatus.INIT),
                        argThat(threshold -> {
                            // 5분 전 시간인지 검증 (오차 10초 허용)
                            LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
                            return threshold.isAfter(fiveMinutesAgo.minusSeconds(10))
                                    && threshold.isBefore(fiveMinutesAgo.plusSeconds(10));
                        }),
                        any()
                );
    }
}