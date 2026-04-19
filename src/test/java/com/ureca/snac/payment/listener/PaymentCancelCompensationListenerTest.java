package com.ureca.snac.payment.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
import com.ureca.snac.payment.service.PaymentInternalService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentCancelCompensationListener 단위 테스트")
class PaymentCancelCompensationListenerTest {

    private PaymentCancelCompensationListener listener;
    private SimpleMeterRegistry meterRegistry;
    private ObjectMapper objectMapper;

    @Mock
    private PaymentInternalService paymentInternalService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        listener = new PaymentCancelCompensationListener(
                paymentInternalService, objectMapper, meterRegistry
        );
    }

    @Test
    @DisplayName("성공 : 유효한 JSON -> processCompensation 호출")
    void shouldCallProcessCompensationForValidJson() {
        // given
        String payload = "{\"paymentId\":1,\"memberId\":1,\"amount\":10000,\"reason\":\"테스트\",\"canceledAt\":\"2025-01-01T00:00:00+09:00\"}";

        // when
        listener.handleCompensationEvent(payload);

        // then: 실제 역직렬화된 이벤트로 processCompensation 호출 확인
        verify(paymentInternalService).processCompensation(
                org.mockito.ArgumentMatchers.argThat(event ->
                        event != null &&
                        event.paymentId().equals(1L) &&
                        event.memberId().equals(1L) &&
                        event.amount().equals(10000L) &&
                        event.reason().equals("테스트") &&
                        event.canceledAt().isEqual(java.time.OffsetDateTime.parse("2025-01-01T00:00:00+09:00"))
                )
        );

        // 메트릭 검증
        assertThat(meterRegistry.get("listener_message_processed_total")
                .tag("result", "success").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("실패 : 잘못된 JSON -> AmqpRejectAndDontRequeueException")
    void shouldThrowAmqpRejectForInvalidJson() {
        // given
        String invalidPayload = "{invalid json}";

        // when, then: 실제 ObjectMapper가 JsonProcessingException을 던져 DLQ로 이동
        assertThatThrownBy(() -> listener.handleCompensationEvent(invalidPayload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        // 메트릭 검증
        assertThat(meterRegistry.get("listener_message_processed_total")
                .tag("result", "dlq").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("실패 : processCompensation 예외 -> 원본 예외 re-throw")
    void shouldRethrowExceptionFromProcessCompensation() {
        // given
        String payload = "{\"paymentId\":1,\"memberId\":1,\"amount\":10000,\"reason\":\"테스트\",\"canceledAt\":\"2025-01-01T00:00:00+09:00\"}";
        doThrow(new RuntimeException("DB 연결 실패"))
                .when(paymentInternalService).processCompensation(
                        org.mockito.ArgumentMatchers.argThat(event ->
                                event != null &&
                                event.paymentId().equals(1L) &&
                                event.memberId().equals(1L) &&
                                event.amount().equals(10000L) &&
                                event.reason().equals("테스트") &&
                                event.canceledAt().isEqual(java.time.OffsetDateTime.parse("2025-01-01T00:00:00+09:00"))
                        )
                );

        // when, then
        assertThatThrownBy(() -> listener.handleCompensationEvent(payload))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 연결 실패");
    }
}
