package com.ureca.snac.common.notification;

import com.ureca.snac.common.notification.dto.SlackMessage;
import com.ureca.snac.config.AsyncConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Slack 알림 전송 서비스
 * <p>
 * 비동기 처리 알림 Executor
 * 재시도 로직 -> 최대 3회, 1초 간격
 * 최종 실패 시 @Recover 처리
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

    /**
     * Slack 메시지 비동기 전송
     * RestClientException 발생 시 최대 3회 재시도
     */
    @Async(AsyncConfig.NOTIFICATION_EXECUTOR_NAME)
    @Retryable(
            retryFor = {RestClientException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000)
    )
    public void sendAsync(SlackMessage message) {
        restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(message)
                .retrieve()
                .toBodilessEntity();

        log.info("[Slack] 메시지 전송 완료");
    }

    // 재시도 모두 실패 시 실행, 알림 실패는 핵심 비즈니스에 영향 없어서 로그만
    @Recover
    public void recover(RestClientException e, SlackMessage message) {
        log.error("[Slack] 메시지 전송 3회 재시도 후 포기 최종 실패. error: {}, message: {}",
                e.getMessage(), message.text());
    }
}