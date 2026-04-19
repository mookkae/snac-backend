package com.ureca.snac.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.infra.dto.response.TossErrorResponse;
import com.ureca.snac.infra.exception.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_API_ERROR;

/**
 * 토스 페이먼츠 API 호출시 발생하는 HTTP 에러 처리
 * API를 호출하는 CLIENT 코드는 에러 처리 로직으로부터 분리
 */
@Slf4j
@RequiredArgsConstructor
public class TossPaymentsErrorHandler implements ResponseErrorHandler {

    private final ObjectMapper objectMapper;

    @Override
    public boolean hasError(final ClientHttpResponse response) throws IOException {
        return response.getStatusCode().is4xxClientError() ||
                response.getStatusCode().is5xxServerError();
    }

    @Override
    public void handleError(final URI url,
                            final HttpMethod method,
                            final ClientHttpResponse response) throws IOException {
        String responseBody = new String(response.getBody().readAllBytes(),
                StandardCharsets.UTF_8);

        log.error("[외부 API 에러] Toss API 호출 실패 Status : {}, URL : {}, responseBody : {}",
                response.getStatusCode(), url, responseBody);

        TossErrorResponse errorResponse;
        try {
            errorResponse = objectMapper.readValue(responseBody, TossErrorResponse.class);

        } catch (JsonProcessingException e) {
            log.error("[외부 API 에러] 토스 응답 파싱 실패. Raw Body: {}", responseBody);
            throw new TossPaymentsApiCallException();
        }

        TossErrorCode errorCode = TossErrorCode.fromCode(errorResponse.code());

        if (errorCode.isRetryable()) {
            throw new TossRetryableException();
        }

        switch (errorCode) {
            case INVALID_CARD_EXPIRATION:
            case INVALID_CARD_NUMBER:
            case REJECT_CARD_COMPANY:
            case INVALID_STOPPED_CARD:
                throw new TossInvalidCardInfoException();

            case NOT_ENOUGH_BALANCE:
            case EXCEED_MAX_DAILY_PAYMENT_COUNT:
            case EXCEED_MAX_PAYMENT_AMOUNT:
                throw new TossNotEnoughBalanceException();

            case INVALID_API_KEY:
            case UNAUTHORIZED_KEY:
            case INVALID_AUTHORIZATION:
                throw new TossInvalidApiKeyException();

            case NOT_FOUND_PAYMENT:
                throw new TossPaymentNotFoundException();

            case ALREADY_PROCESSED_PAYMENT:
                throw new TossAlreadyProcessedPaymentException();

            case ALREADY_CANCELED_PAYMENT:
                throw new TossAlreadyCanceledPaymentException();

            case NOT_CANCELABLE_PAYMENT:
                throw new TossNotCancelablePaymentException();

            default:
                throw new ExternalApiException(PAYMENT_GATEWAY_API_ERROR);
        }
    }
}
