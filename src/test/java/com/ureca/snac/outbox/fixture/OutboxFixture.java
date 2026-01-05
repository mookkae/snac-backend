package com.ureca.snac.outbox.fixture;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.UUID;

// Outbox 테스트 Fixture
public class OutboxFixture {

    public static OutboxBuilder builder() {
        return new OutboxBuilder();
    }

    // 기본 회원가입
    public static Outbox memberJoinInit(Long memberId) {
        return builder()
                .eventType(EventType.MEMBER_JOIN)
                .aggregateType(AggregateType.MEMBER)
                .aggregateId(memberId)
                .status(OutboxStatus.INIT)
                .build();
    }

    public static Outbox memberJoinSendFail(Long memberId) {
        return builder()
                .eventType(EventType.MEMBER_JOIN)
                .aggregateType(AggregateType.MEMBER)
                .aggregateId(memberId)
                .status(OutboxStatus.SEND_FAIL)
                .withId(memberId)  // Publisher용
                .build();
    }

    // 지갑 생성
    public static Outbox walletCreatedInit(Long walletId) {
        return builder()
                .eventType(EventType.WALLET_CREATED)
                .aggregateType(AggregateType.WALLET)
                .aggregateId(walletId)
                .status(OutboxStatus.INIT)
                .build();
    }

    public static class OutboxBuilder {
        private Long id = null;  // 기본값: null (Repository용)
        private String eventId = UUID.randomUUID().toString();
        private EventType eventType = EventType.MEMBER_JOIN;
        private AggregateType aggregateType = AggregateType.MEMBER;
        private Long aggregateId = 1L;
        private String payload;
        private OutboxStatus status = OutboxStatus.INIT;
        private Integer retryCount = 0;
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

        public OutboxBuilder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public OutboxBuilder status(OutboxStatus status) {
            this.status = status;
            if (status == OutboxStatus.PUBLISHED) {
                this.publishedAt = LocalDateTime.now();
            }
            return this;
        }

        public OutboxBuilder retryCount(Integer retryCount) {
            this.retryCount = retryCount;
            return this;
        }

        public Outbox build() {
            if (payload == null) {
                payload = String.format("{\"id\":%d}", aggregateId);
            }

            try {
                Constructor<Outbox> constructor = Outbox.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                Outbox outbox = constructor.newInstance();

                if (id != null) {
                    setField(outbox, "id", id);
                }
                setField(outbox, "eventId", eventId);
                setField(outbox, "eventType", eventType.getTypeName());
                setField(outbox, "aggregateType", aggregateType.getTypeName());
                setField(outbox, "aggregateId", aggregateId);
                setField(outbox, "payload", payload);
                setField(outbox, "status", status);
                setField(outbox, "retryCount", retryCount);
                setField(outbox, "createdAt", LocalDateTime.now());
                setField(outbox, "updatedAt", LocalDateTime.now());

                if (publishedAt != null) {
                    setField(outbox, "publishedAt", publishedAt);
                }

                return outbox;

            } catch (Exception e) {
                throw new RuntimeException("Outbox Fixture 생성 실패", e);
            }
        }

        private void setField(Object target, String fieldName, Object value) {
            try {
                Field field = getField(target.getClass(), fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (Exception e) {
                throw new RuntimeException("필드 설정 실패: " + fieldName, e);
            }
        }

        private Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                Class<?> superClass = clazz.getSuperclass();
                if (superClass != null) {
                    return getField(superClass, fieldName);
                }
                throw e;
            }
        }
    }
}