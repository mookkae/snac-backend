package com.ureca.snac.payment.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
import com.ureca.snac.payment.service.PaymentInternalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentCancelCompensationListener 단위 테스트")
class PaymentCancelCompensationListenerTest {

    @InjectMocks
    private PaymentCancelCompensationListener listener;

    @Mock
    private PaymentInternalService paymentInternalService;

    @Mock
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("성공 : 유효한 JSON -> processCompensation 호출")
    void shouldCallProcessCompensationForValidJson() throws Exception {
        // given
        String payload = "{\"paymentId\":1,\"memberId\":1,\"amount\":10000,\"reason\":\"테스트\",\"canceledAt\":\"2025-01-01T00:00:00+09:00\"}";
        PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                1L, 1L, 10000L, "테스트", OffsetDateTime.now());

        given(objectMapper.readValue(payload, PaymentCancelCompensationEvent.class))
                .willReturn(event);

        // when
        listener.handleCompensationEvent(payload);

        // then
        verify(paymentInternalService).processCompensation(event);
    }

    @Test
    @DisplayName("실패 : 잘못된 JSON -> AmqpRejectAndDontRequeueException")
    void shouldThrowAmqpRejectForInvalidJson() throws Exception {
        // given
        String invalidPayload = "{invalid json}";

        given(objectMapper.readValue(invalidPayload, PaymentCancelCompensationEvent.class))
                .willThrow(new JsonProcessingException("파싱 실패") {
                });

        // when, then
        assertThatThrownBy(() -> listener.handleCompensationEvent(invalidPayload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    @Test
    @DisplayName("실패 : processCompensation 예외 -> 원본 예외 re-throw")
    void shouldRethrowExceptionFromProcessCompensation() throws Exception {
        // given
        String payload = "{\"paymentId\":1,\"memberId\":1,\"amount\":10000,\"reason\":\"테스트\",\"canceledAt\":\"2025-01-01T00:00:00+09:00\"}";
        PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                1L, 1L, 10000L, "테스트", OffsetDateTime.now());

        given(objectMapper.readValue(payload, PaymentCancelCompensationEvent.class))
                .willReturn(event);
        doThrow(new RuntimeException("DB 연결 실패"))
                .when(paymentInternalService).processCompensation(event);

        // when, then
        assertThatThrownBy(() -> listener.handleCompensationEvent(payload))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB 연결 실패");
    }
}
