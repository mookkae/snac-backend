package com.ureca.snac.outbox.entity;

import com.ureca.snac.common.BaseTimeEntity;
import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "outbox",
        indexes = {
                // INIT 조회 , Archiving 전용
                @Index(name = "idx_status_created",
                        columnList = "status, created_at"),

                // SEND_FAIL 재시도 전용
                @Index(name = "idx_status_retry",
                        columnList = "status, retry_count"),

                // Aggregate 추적용
                @Index(name = "idx_aggregate",
                        columnList = "aggregate_type, aggregate_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Outbox extends BaseTimeEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "outbox_id")
    private Long id;

    @Column(nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(nullable = false, length = 255)
    private String eventType;

    @Column(nullable = false, length = 50)
    private String aggregateType;

    @Column(nullable = false)
    private Long aggregateId;

    @Lob
    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private Integer retryCount;

    private LocalDateTime publishedAt;

    @Builder
    private Outbox(String eventId, String eventType, String aggregateType,
                   Long aggregateId, String payload, OutboxStatus status, Integer retryCount
    ) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = status;
        this.retryCount = retryCount;
    }

    // 팩토리 메서드
    public static Outbox create(
            EventType eventType,
            AggregateType aggregateType,
            Long aggregateId,
            String payload
    ) {
        return Outbox.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType.getTypeName())
                .aggregateType(aggregateType.getTypeName())
                .aggregateId(aggregateId)
                .payload(payload)
                .status(OutboxStatus.INIT)
                .retryCount(0)
                .build();
    }

    // 상태 변경 메서드
    public void markAsPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    public void markAsFailed() {
        this.status = OutboxStatus.SEND_FAIL;
    }

    public void incrementRetryCount() {
        this.retryCount++;
    }
}