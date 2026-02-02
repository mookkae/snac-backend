package com.ureca.snac.outbox.fixture;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.support.TestReflectionUtils;

import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox 테스트 Fixture
 * OutboxRepositoryTest, OutboxPublisherTest
 */
public class OutboxFixture {

    public static OutboxBuilder builder() {
        return new OutboxBuilder();
    }

    // Repository 테스트용 (ID 없음)
    public static Outbox memberJoinInit(Long memberId) {
        return builder()
                .eventType(EventType.MEMBER_JOIN)
                .aggregateType(AggregateType.MEMBER)
                .aggregateId(memberId)
                .status(OutboxStatus.INIT)
                .build();
    }

    public static Outbox failedWithRetry(Long memberId, int retryCount) {
        return builder()
                .eventType(EventType.MEMBER_JOIN)
                .aggregateType(AggregateType.MEMBER)
                .aggregateId(memberId)
                .status(OutboxStatus.SEND_FAIL)
                .retryCount(retryCount)
                .build();
    }

    /**
     * 발행 완료된 오래된 이벤트 (Archiving 대상)
     *
     * @param memberId 회원 ID
     * @param daysAgo  며칠 전에 발행되었는지
     */
    public static Outbox publishedOld(Long memberId, int daysAgo) {
        LocalDateTime publishedTime = LocalDateTime.now().minusDays(daysAgo);
        return builder()
                .eventType(EventType.MEMBER_JOIN)
                .aggregateType(AggregateType.MEMBER)
                .aggregateId(memberId)
                .status(OutboxStatus.PUBLISHED)
                .publishedAt(publishedTime)
                .createdAt(publishedTime.minusMinutes(1))  // 발행보다 약간 이전
                .build();
    }

    // Publisher 테스트용 (ID 있음 - 런던파 테스트)
    public static Outbox failedWithRetryWithId(Long id, Long memberId, int retryCount) {
        return builder()
                .withId(id)
                .eventType(EventType.MEMBER_JOIN)
                .aggregateType(AggregateType.MEMBER)
                .aggregateId(memberId)
                .status(OutboxStatus.SEND_FAIL)
                .retryCount(retryCount)
                .build();
    }

    // 오래된 INIT 상태 (id 있음 - Publisher 테스트용)
    public static Outbox staleInitWithId(Long id, Long memberId, int minutesAgo) {
        return builder()
                .withId(id)
                .eventType(EventType.MEMBER_JOIN)
                .aggregateType(AggregateType.MEMBER)
                .aggregateId(memberId)
                .status(OutboxStatus.INIT)
                .createdAt(LocalDateTime.now().minusMinutes(minutesAgo))
                .build();
    }

    // 지갑 생성 SEND_FAIL 상태 (id 있음 - Publisher 테스트용)
    public static Outbox walletCreatedFailedWithId(Long id, Long walletId, int retryCount) {
        return builder()
                .withId(id)
                .eventType(EventType.WALLET_CREATED)
                .aggregateType(AggregateType.WALLET)
                .aggregateId(walletId)
                .status(OutboxStatus.SEND_FAIL)
                .retryCount(retryCount)
                .build();
    }

    public static class OutboxBuilder {
        private Long id = null;
        private String eventId = UUID.randomUUID().toString();
        private EventType eventType = EventType.MEMBER_JOIN;
        private AggregateType aggregateType = AggregateType.MEMBER;
        private Long aggregateId = 1L;
        private String payload;
        private OutboxStatus status = OutboxStatus.INIT;
        private Integer retryCount = 0;
        private LocalDateTime createdAt = null;
        private LocalDateTime publishedAt;

        public OutboxBuilder withId(Long id) {
            this.id = id;
            return this;
        }

        public OutboxBuilder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        public OutboxBuilder eventType(EventType eventType) {
            this.eventType = eventType;
            return this;
        }

        public OutboxBuilder aggregateType(AggregateType aggregateType) {
            this.aggregateType = aggregateType;
            return this;
        }

        public OutboxBuilder aggregateId(Long aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        public OutboxBuilder status(OutboxStatus status) {
            this.status = status;
            if (status == OutboxStatus.PUBLISHED && this.publishedAt == null) {
                this.publishedAt = LocalDateTime.now();
            }
            return this;
        }

        public OutboxBuilder retryCount(Integer retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public OutboxBuilder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public OutboxBuilder publishedAt(LocalDateTime publishedAt) {
            this.publishedAt = publishedAt;
            return this;
        }

        public Outbox build() {
            if (payload == null) {
                payload = String.format("{\"id\":%d}", aggregateId);
            }

            LocalDateTime finalCreatedAt = createdAt != null ? createdAt : LocalDateTime.now();

            try {
                Constructor<Outbox> constructor = Outbox.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                Outbox outbox = constructor.newInstance();

                if (id != null) {
                    TestReflectionUtils.setField(outbox, "id", id);
                }
                TestReflectionUtils.setField(outbox, "eventId", eventId);
                TestReflectionUtils.setField(outbox, "eventType", eventType.getTypeName());
                TestReflectionUtils.setField(outbox, "aggregateType", aggregateType.getTypeName());
                TestReflectionUtils.setField(outbox, "aggregateId", aggregateId);
                TestReflectionUtils.setField(outbox, "payload", payload);
                TestReflectionUtils.setField(outbox, "status", status);
                TestReflectionUtils.setField(outbox, "retryCount", retryCount);
                TestReflectionUtils.setField(outbox, "createdAt", finalCreatedAt);
                TestReflectionUtils.setField(outbox, "updatedAt", finalCreatedAt);

                if (publishedAt != null) {
                    TestReflectionUtils.setField(outbox, "publishedAt", publishedAt);
                }
                return outbox;
            } catch (Exception e) {
                throw new RuntimeException("Outbox Fixture 생성 실패", e);
            }
        }
    }
}
