package com.ureca.snac.common.notification;

import com.ureca.snac.common.notification.dto.SlackMessage;
import com.ureca.snac.config.AsyncConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Slack 알림 전송 서비스
 * <p>
 * 비동기 처리 (@Async)
 * 재시도 로직 (@Retryable) 최대 3회, 1초간격
 * 실패 시 로그만 기록
 */
@Slf4j
@Component
public class SlackNotifier {

    private final RestClient restClient;

    public SlackNotifier(
            RestClient.Builder restClientBuilder,
            @Value("${slack.webhook.url}") String webhookUrl
    ) {
        this.restClient = restClientBuilder
                .baseUrl(webhookUrl)
                .build();
    }


    @Async(AsyncConfig.NOTIFICATION_EXECUTOR_NAME)
    @Retryable(
            retryFor = {RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000)
    )
    public void sendAsync(SlackMessage message) {

        try {
            restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .toBodilessEntity();

            log.info("[Slack] 메시지 전송 완료");

        } catch (RestClientException e) {
            log.error("[Slack] 메시지 전송 실패. 재시도 중 error : {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("[Slack] 메시지 전송 실패 (재시도 불가)", e);
        }
    }
}