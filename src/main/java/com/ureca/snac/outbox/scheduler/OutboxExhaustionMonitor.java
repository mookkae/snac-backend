package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.common.notification.SlackNotifier;
import com.ureca.snac.common.notification.dto.SlackAttachment;
import com.ureca.snac.common.notification.dto.SlackField;
import com.ureca.snac.common.notification.dto.SlackMessage;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.outbox.repository.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbox 소진 이벤트 감시
 * <p>
 * 재시도 한도를 다 쓴 행은 폴러가 다시 집지 않으므로 사람에게 알린다
 * 개수 변화 시에만 알림 (중복 방지)
 */
@Slf4j
@Component
public class OutboxExhaustionMonitor {

    private final OutboxRepository outboxRepository;
    private final SlackNotifier slackNotifier;
    private final int maxRetryCount;

    // 중복 알림 방지용 (마지막 알림 개수)
    private long lastAlertedCount = 0;

    public OutboxExhaustionMonitor(
            OutboxRepository outboxRepository,
            SlackNotifier slackNotifier,
            @Value("${outbox.publisher.max-retry}") int maxRetryCount
    ) {
        this.outboxRepository = outboxRepository;
        this.slackNotifier = slackNotifier;
        this.maxRetryCount = maxRetryCount;
    }

    @Scheduled(fixedDelayString = "${outbox.dlq-monitor.interval}")
    public void monitor() {
        try {
            long count = outboxRepository.countByStatusAndRetryCountGreaterThanEqual(
                    OutboxStatus.SEND_FAIL, maxRetryCount);

            if (count > 0 && count != lastAlertedCount) {
                log.warn("[Outbox 소진 감지] count: {}", count);
                slackNotifier.sendAsync(buildSlackMessage(count));
            }
            lastAlertedCount = count;

        } catch (Exception e) {
            log.error("[Outbox 소진 감시] 실패", e);
        }
    }

    // 운영자 즉시 액션 가능한 가이드 포함
    private SlackMessage buildSlackMessage(long count) {
        List<SlackField> fields = List.of(
                SlackField.of("소진 이벤트", count + "건"),
                SlackField.longField("조치",
                        """
                                1. outbox 테이블에서 SEND_FAIL, retry_count 상한 행 확인
                                2. payload 원인 확인
                                3. 수정 후 retry_count 초기화 또는 폐기 결정
                                """
                )
        );

        return SlackMessage.of("경고 Outbox 소진 이벤트 감지", SlackAttachment.danger(fields));
    }
}
