package com.ureca.snac.outbox.service;

import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.fixture.OutboxFixture;
import com.ureca.snac.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * OutboxPublisher 스케줄러 단위 테스트
 * <p>
 * SEND_FAIL 이벤트 재발행
 * 오래된 INIT 이벤트 재발행 (서버 장애 복구)
 * maxRetry 제한 (10회)
 * 배치 처리 (100개씩)
 * 실패 시 상태 업데이트
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private OutboxPublisher outboxPublisher;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private OutboxStatusUpdater outboxStatusUpdater;

    @Mock
    private RabbitTemplate rabbitTemplate;

    private static final int MAX_RETRY_COUNT = 10;
    private static final int BATCH_SIZE = 100;
    private static final int STALE_THRESHOLD_MINUTES = 5;

    @BeforeEach
    void setUp() {
        outboxPublisher = new OutboxPublisher(
                outboxRepository,
                rabbitTemplate,
                outboxStatusUpdater,
                BATCH_SIZE,
                STALE_THRESHOLD_MINUTES,
                MAX_RETRY_COUNT
        );
    }

    // 성공 시나리오
    @Test
    @DisplayName("성공 : SEND_FAIL 이벤트 재발행 -> PUBLISHED")
    void publishPendingEvents_SendFail_Success() {
        // given : SEND_FAIL 2개 (id 있음)
        Outbox memberEvent = OutboxFixture.failedWithRetryWithId(1L, 1L, 1);
        Outbox walletEvent = OutboxFixture.walletCreatedFailedWithId(2L, 2L, 2);

        given(outboxRepository.findPendingEvents(
                eq(OutboxStatus.SEND_FAIL),
                eq(OutboxStatus.INIT),
                any(LocalDateTime.class),
                eq(MAX_RETRY_COUNT),
                eq(PageRequest.of(0, BATCH_SIZE))
        )).willReturn(List.of(memberEvent, walletEvent));

        // when
        outboxPublisher.publishPendingEvents();

        // then : RabbitMQ 발행 2회
        verify(rabbitTemplate, times(2))
                .convertAndSend(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(MessagePostProcessor.class)
                );

        // 발행 상태 업데이트 2회
        verify(outboxStatusUpdater, times(1))
                .markAsPublished(1L);
        verify(outboxStatusUpdater, times(1))
                .markAsPublished(2L);

        // 실패 처리 없음
        verify(outboxStatusUpdater, never())
                .markAsFailed(anyLong());
    }

    @Test
    @DisplayName("성공 : 오래된 INIT 이벤트 재발행 (서버 장애 복구)")
    void publishPendingEvents_StaleInit_Success() {
        // given : 10분 전 INIT (id 있음)
        Outbox staleInit = OutboxFixture.staleInitWithId(1L, 1L, 10);

        given(outboxRepository.findPendingEvents(
                eq(OutboxStatus.SEND_FAIL),
                eq(OutboxStatus.INIT),
                any(LocalDateTime.class),
                eq(MAX_RETRY_COUNT),
                eq(PageRequest.of(0, BATCH_SIZE))
        )).willReturn(List.of(staleInit));

        // when
        outboxPublisher.publishPendingEvents();

        // then
        verify(rabbitTemplate, times(1))
                .convertAndSend(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(MessagePostProcessor.class)
                );

        verify(outboxStatusUpdater, times(1))
                .markAsPublished(1L);
    }

    @Test
    @DisplayName("성공 : 이벤트 없으면 조기 종료")
    void publishPendingEvents_EmptyList_NoAction() {
        // given
        given(outboxRepository.findPendingEvents(
                any(), any(), any(), anyInt(), any()
        )).willReturn(List.of());

        // when
        outboxPublisher.publishPendingEvents();

        // then
        verify(rabbitTemplate, never())
                .convertAndSend(anyString(), anyString(), anyString(), any(MessagePostProcessor.class));

        verify(outboxStatusUpdater, never())
                .markAsPublished(anyLong());
        verify(outboxStatusUpdater, never())
                .markAsFailed(anyLong());
    }

    // 실패 시나리오
    @Test
    @DisplayName("실패 : RabbitMQ 다운 -> SEND_FAIL 업데이트")
    void publishPendingEvents_RabbitMQDown_MarkAsFailed() {
        // given
        Outbox outbox = OutboxFixture.failedWithRetryWithId(1L, 1L, 1);

        given(outboxRepository.findPendingEvents(
                any(), any(), any(), anyInt(), any()
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

        // when
        outboxPublisher.publishPendingEvents();

        // then
        verify(outboxStatusUpdater, times(1))
                .markAsFailed(1L);

        // PUBLISHED 업데이트 안 됨
        verify(outboxStatusUpdater, never())
                .markAsPublished(anyLong());
    }

    @Test
    @DisplayName("실패 : 일부 성공, 일부 실패 (부분 실패)")
    void publishPendingEvents_PartialFailure() {
        // given
        Outbox success1 = OutboxFixture.failedWithRetryWithId(1L, 1L, 0);
        Outbox failure = OutboxFixture.failedWithRetryWithId(2L, 2L, 1);
        Outbox success2 = OutboxFixture.failedWithRetryWithId(3L, 3L, 0);

        given(outboxRepository.findPendingEvents(
                any(), any(), any(), anyInt(), any()
        )).willReturn(List.of(success1, failure, success2));

        // 2번째만 실패
        doNothing()
                .doThrow(new RuntimeException("Network error"))
                .doNothing()
                .when(rabbitTemplate)
                .convertAndSend(
                        anyString(),
                        anyString(),
                        anyString(),
                        any(MessagePostProcessor.class)
                );

        // when
        outboxPublisher.publishPendingEvents();

        // then: 성공 2개
        verify(outboxStatusUpdater, times(1))
                .markAsPublished(1L);
        verify(outboxStatusUpdater, times(1))
                .markAsPublished(3L);

        // 실패 1개
        verify(outboxStatusUpdater, times(1))
                .markAsFailed(2L);
    }

    @Test
    @DisplayName("검증 : maxRetry 파라미터 전달")
    void publishPendingEvents_PassMaxRetryParameter() {
        // given
        given(outboxRepository.findPendingEvents(
                any(), any(), any(), anyInt(), any()
        )).willReturn(List.of());

        // when
        outboxPublisher.publishPendingEvents();

        // then
        verify(outboxRepository, times(1))
                .findPendingEvents(
                        eq(OutboxStatus.SEND_FAIL),
                        eq(OutboxStatus.INIT),
                        any(LocalDateTime.class),
                        eq(MAX_RETRY_COUNT),
                        eq(PageRequest.of(0, BATCH_SIZE))
                );
    }

    @Test
    @DisplayName("검증 : 5분 이전 threshold들 전달")
    void publishPendingEvents_PassThresholdParameter() {
        // given
        given(outboxRepository.findPendingEvents(
                any(), any(), any(), anyInt(), any()
        )).willReturn(List.of());

        // when
        outboxPublisher.publishPendingEvents();

        // then
        verify(outboxRepository, times(1))
                .findPendingEvents(
                        eq(OutboxStatus.SEND_FAIL),
                        eq(OutboxStatus.INIT),
                        argThat(threshold -> {
                            LocalDateTime fiveMinutesAgo = LocalDateTime.now().minusMinutes(5);
                            return threshold.isAfter(fiveMinutesAgo.minusSeconds(10))
                                    && threshold.isBefore(fiveMinutesAgo.plusSeconds(10));
                        }),
                        eq(MAX_RETRY_COUNT),
                        any()
                );
    }

    @Test
    @DisplayName("검증 : 배치 크기 100개 제한")
    void publishPendingEvents_BatchSizeLimit() {
        // given
        given(outboxRepository.findPendingEvents(
                any(), any(), any(), anyInt(), any()
        )).willReturn(List.of());

        // when
        outboxPublisher.publishPendingEvents();

        // then
        verify(outboxRepository, times(1))
                .findPendingEvents(
                        eq(OutboxStatus.SEND_FAIL),
                        eq(OutboxStatus.INIT),
                        any(LocalDateTime.class),
                        eq(MAX_RETRY_COUNT),
                        eq(PageRequest.of(0, BATCH_SIZE))
                );
    }
}