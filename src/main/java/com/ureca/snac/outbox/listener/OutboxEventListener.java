package com.ureca.snac.outbox.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.common.event.DomainEvent;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.event.OutboxScheduledEvent;
import com.ureca.snac.outbox.exception.OutboxSerializationException;
import com.ureca.snac.outbox.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * 도메인 이벤트를 Outbox 테이블에 저장하는 리스너
 * BEFORE_COMMIT 단계에서 실행되어 비즈니스 로직과 같은 트랜잭션 보장
 * Hybrid Push 대상 이벤트는 추가로 OutboxScheduledEvent 발행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventListener {

    private final OutboxRepository outboxRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /**
     * Hybrid Push 대상 이벤트 화이트리스트
     * 속도가 중요한 이벤트만 등록 (회원가입, 지갑 생성 등)
     */
    private static final Set<EventType> HYBRID_PUSH_EVENTS = Set.of(
            EventType.MEMBER_JOIN,
            EventType.WALLET_CREATED
    );

    /**
     * 도메인 이벤트를 Outbox에 저장
     *
     * @param event 도메인 이벤트
     * @throws OutboxSerializationException 직렬화 실패 시 (전체 트랜잭션 롤백)
     */
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void saveToOutbox(DomainEvent event) {
        try {
            log.debug("[Outbox] 이벤트 저장 시작. eventType: {}, aggregateId: {}",
                    event.getEventType().getTypeName(), event.getAggregateId());

            String payload = objectMapper.writeValueAsString(event);

            Outbox outbox = Outbox.create(
                    event.getEventType(),
                    event.getAggregateType(),
                    event.getAggregateId(),
                    payload
            );

            outboxRepository.save(outbox);

            log.info("[Outbox] 이벤트 저장 완료. outboxId: {}, eventType: {}",
                    outbox.getId(), outbox.getEventType());

            // Hybrid Push 대상이면 릴레이 이벤트 발행
            if (isHybridPushTarget(event.getEventType())) {
                log.debug("[Outbox] Hybrid Push 대상. 릴레이 이벤트 발행. outboxId: {}",
                        outbox.getId());

                eventPublisher.publishEvent(
                        new OutboxScheduledEvent(
                                outbox.getId(),
                                outbox.getEventType(),
                                outbox.getAggregateType(),
                                outbox.getAggregateId(),
                                payload
                        )
                );
            }

        } catch (JsonProcessingException e) {
            log.error("[Outbox] JSON 직렬화 실패. 전체 트랜잭션 롤백. eventType: {}, aggregateId: {}",
                    event.getEventType().getTypeName(), event.getAggregateId(), e);

            throw new OutboxSerializationException(
                    "이벤트 직렬화 실패: " + event.getEventType().getTypeName(), e
            );
        }
    }

    /**
     * Hybrid Push 대상 이벤트 판별
     *
     * @param eventType 이벤트 타입
     * @return Hybrid Push 대상 여부
     */
    private boolean isHybridPushTarget(EventType eventType) {
        return HYBRID_PUSH_EVENTS.contains(eventType);
    }
}