package com.ureca.snac.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.infra.dto.response.TossErrorResponse;
import com.ureca.snac.payment.exception.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("TossPaymentsErrorHandler 단위 테스트")
class TossPaymentsErrorHandlerTest {

    private TossPaymentsErrorHandler errorHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        errorHandler = new TossPaymentsErrorHandler();
    }

    @Nested
    @DisplayName("hasError 메서드")
    class HasErrorTest {

        @Test
        @DisplayName("성공 : 4xx 응답 -> true")
        void hasError_4xxResponse_ReturnsTrue() throws IOException {
            ClientHttpResponse response = mockResponse(HttpStatus.BAD_REQUEST);
            assertThat(errorHandler.hasError(response)).isTrue();
        }

        @Test
        @DisplayName("성공 : 5xx 응답 -> true")
        void hasError_5xxResponse_ReturnsTrue() throws IOException {
            ClientHttpResponse response = mockResponse(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(errorHandler.hasError(response)).isTrue();
        }

        @Test
        @DisplayName("성공 : 2xx 응답 -> false")
        void hasError_2xxResponse_ReturnsFalse() throws IOException {
            ClientHttpResponse response = mockResponse(HttpStatus.OK);
            assertThat(errorHandler.hasError(response)).isFalse();
        }
    }

    @Nested
    @DisplayName("handleError 메서드")
    class HandleErrorTest {

        private static final URI TEST_URI = URI.create("https://api.tosspayments.com/v1/payments/confirm");

        @Test
        @DisplayName("성공 : retryable 에러코드 -> TossRetryableException")
        void handleError_RetryableCode_ThrowsTossRetryableException() throws Exception {
            ClientHttpResponse response = mockResponseWithBody(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    new TossErrorResponse("SERVICE_UNAVAILABLE", "서비스 일시 불가", null)
            );

            assertThatThrownBy(() -> errorHandler.handleError(TEST_URI, HttpMethod.POST, response))
                    .isInstanceOf(TossRetryableException.class);
        }

        @Test
        @DisplayName("성공 : INVALID_CARD_NUMBER -> TossInvalidCardInfoException")
        void handleError_InvalidCardNumber_ThrowsTossInvalidCardInfoException() throws Exception {
            ClientHttpResponse response = mockResponseWithBody(
                    HttpStatus.BAD_REQUEST,
                    new TossErrorResponse("INVALID_CARD_NUMBER", "유효하지 않은 카드번호", null)
            );

            assertThatThrownBy(() -> errorHandler.handleError(TEST_URI, HttpMethod.POST, response))
                    .isInstanceOf(TossInvalidCardInfoException.class);
        }

        @Test
        @DisplayName("성공 : REJECT_CARD_COMPANY -> TossInvalidCardInfoException")
        void handleError_RejectCardCompany_ThrowsTossInvalidCardInfoException() throws Exception {
            ClientHttpResponse response = mockResponseWithBody(
                    HttpStatus.BAD_REQUEST,
                    new TossErrorResponse("REJECT_CARD_COMPANY", "카드사 거절", null)
            );

            assertThatThrownBy(() -> errorHandler.handleError(TEST_URI, HttpMethod.POST, response))
                    .isInstanceOf(TossInvalidCardInfoException.class);
        }

        @Test
        @DisplayName("성공 : NOT_ENOUGH_BALANCE -> TossNotEnoughBalanceException")
        void handleError_NotEnoughBalance_ThrowsTossNotEnoughBalanceException() throws Exception {
            ClientHttpResponse response = mockResponseWithBody(
                    HttpStatus.BAD_REQUEST,
                    new TossErrorResponse("NOT_ENOUGH_BALANCE", "잔액 부족", null)
            );

            assertThatThrownBy(() -> errorHandler.handleError(TEST_URI, HttpMethod.POST, response))
                    .isInstanceOf(TossNotEnoughBalanceException.class);
        }

        @Test
        @DisplayName("성공 : EXCEED_MAX_PAYMENT_AMOUNT -> TossNotEnoughBalanceException")
        void handleError_ExceedMaxPayment_ThrowsTossNotEnoughBalanceException() throws Exception {
            ClientHttpResponse response = mockResponseWithBody(
                    HttpStatus.BAD_REQUEST,
                    new TossErrorResponse("EXCEED_MAX_PAYMENT_AMOUNT", "최대 결제 금액 초과", null)
            );

            assertThatThrownBy(() -> errorHandler.handleError(TEST_URI, HttpMethod.POST, response))
                    .isInstanceOf(TossNotEnoughBalanceException.class);
        }

        @Test
        @DisplayName("성공 : INVALID_API_KEY -> TossInvalidApiKeyException")
        void handleError_InvalidApiKey_ThrowsTossInvalidApiKeyException() throws Exception {
            ClientHttpResponse response = mockResponseWithBody(
                    HttpStatus.UNAUTHORIZED,
                    new TossErrorResponse("INVALID_API_KEY", "잘못된 API 키", null)
            );

            assertThatThrownBy(() -> errorHandler.handleError(TEST_URI, HttpMethod.POST, response))
                    .isInstanceOf(TossInvalidApiKeyException.class);
        }

        @Test
        @DisplayName("성공 : UNAUTHORIZED_KEY -> TossInvalidApiKeyException")
        void handleError_UnauthorizedKey_ThrowsTossInvalidApiKeyException() throws Exception {
            ClientHttpResponse response = mockResponseWithBody(
                    HttpStatus.UNAUTHORIZED,
                    new TossErrorResponse("UNAUTHORIZED_KEY", "인증되지 않은 키", null)
            );

            assertThatThrownBy(() -> errorHandler.handleError(TEST_URI, HttpMethod.POST, response))
                    .isInstanceOf(TossInvalidApiKeyException.class);
        }

        @Test
        @DisplayName("성공 : ALREADY_PROCESSED_PAYMENT -> PaymentAlreadyProcessedPaymentException")
        void handleError_AlreadyProcessed_ThrowsPaymentAlreadyProcessedException() throws Exception {
            ClientHttpResponse response = mockResponseWithBody(
                    HttpStatus.CONFLICT,
                    new TossErrorResponse("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제", null)
            );

            assertThatThrownBy(() -> errorHandler.handleError(TEST_URI, HttpMethod.POST, response))
                    .isInstanceOf(PaymentAlreadyProcessedPaymentException.class);
        }

        @Test
        @DisplayName("성공 : 알 수 없는 non-retryable 코드 -> ExternalApiException")
        void handleError_UnknownNonRetryableCode_ThrowsExternalApiException() throws Exception {
            ClientHttpResponse response = mockResponseWithBody(
                    HttpStatus.BAD_REQUEST,
                    new TossErrorResponse("FORBIDDEN_REQUEST", "금지된 요청", null)
            );

            assertThatThrownBy(() -> errorHandler.handleError(TEST_URI, HttpMethod.POST, response))
                    .isInstanceOf(ExternalApiException.class);
        }

        @Test
        @DisplayName("실패 : JSON 파싱 실패 -> TossPaymentsAPiCallException")
        void handleError_JsonParseFailure_ThrowsTossPaymentsApiCallException() throws Exception {
            ClientHttpResponse response = mockResponseWithRawBody(
                    HttpStatus.BAD_REQUEST,
                    "this is not valid json"
            );

            assertThatThrownBy(() -> errorHandler.handleError(TEST_URI, HttpMethod.POST, response))
                    .isInstanceOf(TossPaymentsAPiCallException.class);
        }
    }

    private ClientHttpResponse mockResponse(HttpStatus status) throws IOException {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        given(response.getStatusCode()).willReturn(status);
        return response;
    }

    private ClientHttpResponse mockResponseWithBody(HttpStatus status, TossErrorResponse errorResponse)
            throws Exception {
        String json = objectMapper.writeValueAsString(errorResponse);
        return mockResponseWithRawBody(status, json);
    }

    private ClientHttpResponse mockResponseWithRawBody(HttpStatus status, String body) throws IOException {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        given(response.getStatusCode()).willReturn(status);
        given(response.getBody())
                .willReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }
}
