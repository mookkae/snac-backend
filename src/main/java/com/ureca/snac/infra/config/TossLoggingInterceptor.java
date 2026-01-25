package com.ureca.snac.infra.config;

import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 토스 페이먼츠 API 호출에 대한 감사 로깅 인터셉터
 * 요청/응답 본문, 소요시간, 상관관계 ID 기록
 */
@Slf4j
public class TossLoggingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    @NonNull
    public ClientHttpResponse intercept(
            @NonNull HttpRequest request,
            byte @NonNull [] body,
            @NonNull ClientHttpRequestExecution execution) throws IOException {

        String correlationId = UUID.randomUUID().toString().substring(0, 8);
        long startTime = System.currentTimeMillis();

        // 요청 로깅 (민감 정보 마스킹)
        logRequest(correlationId, request, body);

        try {
            ClientHttpResponse response = execution.execute(request, body);
            long duration = System.currentTimeMillis() - startTime;

            // 응답을 버퍼링하여 여러 번 읽기 가능하게 함
            BufferingClientHttpResponseWrapper bufferedResponse =
                    new BufferingClientHttpResponseWrapper(response);

            // 응답 로깅 (이제 본문도 로깅 가능)
            logResponse(correlationId, bufferedResponse, duration);

            return bufferedResponse;
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[TOSS-API-AUDIT] correlationId={} | ERROR | duration={}ms | exception={}",
                    correlationId, duration, e.getMessage());
            throw e;
        }
    }

    private void logRequest(String correlationId, HttpRequest request, byte[] body) {
        String requestBody = new String(body, StandardCharsets.UTF_8);
        String maskedBody = maskSensitiveData(requestBody);

        log.info("[TOSS-API-AUDIT] correlationId={} | REQUEST | method={} | uri={} | body={}",
                correlationId,
                request.getMethod(),
                request.getURI(),
                maskedBody);
    }

    private void logResponse(String correlationId, BufferingClientHttpResponseWrapper response, long duration) {
        try {
            String responseBody = maskSensitiveData(response.getBodyAsString());

            log.info("[TOSS-API-AUDIT] correlationId={} | RESPONSE | status={} | duration={}ms | body={}",
                    correlationId,
                    response.getStatusCode().value(),
                    duration,
                    responseBody);
        } catch (IOException e) {
            log.warn("[TOSS-API-AUDIT] correlationId={} | RESPONSE | status=UNKNOWN | duration={}ms | error={}",
                    correlationId, duration, e.getMessage());
        }
    }

    /**
     * 민감 정보 마스킹 (카드번호, API키 등)
     */
    private String maskSensitiveData(String data) {
        if (data == null || data.isEmpty()) {
            return data;
        }
        // paymentKey 마스킹 (처음 8자만 표시)
        return data.replaceAll("(\"paymentKey\"\\s*:\\s*\")([^\"]{8})([^\"]*)(\")",
                "$1$2****$4");
    }
}
