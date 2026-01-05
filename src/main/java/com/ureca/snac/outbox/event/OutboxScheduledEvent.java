package com.ureca.snac.outbox.event;

/**
 * 이벤트 릴레이용 내부 DTO
 * BEFORE_COMMIT 단계에서 저장된 Outbox의 정보를
 * AFTER_COMMIT 단계의 AsyncPublisher에게 전달하는 '바통' 역할
 * 도메인 로직이 아니므로 인프라 계층(Outbox) 내부에서만 사용
 */
public record OutboxScheduledEvent(
        Long outboxId, // Outbox 의 PK
        String eventType, // 이벤트 타입 식별자로 라우팅 키 결정에 사용
        String aggregateType, // exchange 결정에 사용
        Long aggregateId, // 순서 및 필터링 추적 용도
        String payload // 직렬화된 JSON
) {
}