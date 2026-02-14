package com.ureca.snac.outbox.entity;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Outbox 엔티티 단위 테스트")
class OutboxTest {

    @Nested
    @DisplayName("create 팩토리 메서드")
    class CreateTest {

        @Test
        @DisplayName("성공 : MEMBER_JOIN 이벤트로 Outbox 생성")
        void create_WithValidInput_CreatesOutbox() {
            // given
            EventType eventType = EventType.MEMBER_JOIN;
            AggregateType aggregateType = AggregateType.MEMBER;
            Long aggregateId = 1L;
            String payload = "{\"id\":1}";

            // when
            Outbox outbox = Outbox.create(eventType, aggregateType, aggregateId, payload);

            // then
            assertThat(outbox.getEventId()).isNotNull();
            assertThat(outbox.getEventId()).hasSize(36);
            assertThat(outbox.getEventType()).isEqualTo(eventType.getTypeName());
            assertThat(outbox.getAggregateType()).isEqualTo(aggregateType.getTypeName());
            assertThat(outbox.getAggregateId()).isEqualTo(aggregateId);
            assertThat(outbox.getPayload()).isEqualTo(payload);
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.INIT);
            assertThat(outbox.getRetryCount()).isZero();
        }

        @Test
        @DisplayName("성공 : 매 생성마다 고유한 eventId 생성")
        void create_CalledTwice_GeneratesUniqueEventIds() {
            // when
            Outbox outbox1 = Outbox.create(EventType.MEMBER_JOIN, AggregateType.MEMBER, 1L, "{}");
            Outbox outbox2 = Outbox.create(EventType.MEMBER_JOIN, AggregateType.MEMBER, 1L, "{}");

            // then
            assertThat(outbox1.getEventId()).isNotEqualTo(outbox2.getEventId());
        }

        @Test
        @DisplayName("성공 : WALLET_CREATED 이벤트로 Outbox 생성")
        void create_WithWalletCreatedEvent_CreatesOutbox() {
            // when
            Outbox outbox = Outbox.create(EventType.WALLET_CREATED, AggregateType.WALLET, 10L, "{\"walletId\":10}");

            // then
            assertThat(outbox.getEventType()).isEqualTo(EventType.WALLET_CREATED.getTypeName());
            assertThat(outbox.getAggregateType()).isEqualTo(AggregateType.WALLET.getTypeName());
        }
    }
}
