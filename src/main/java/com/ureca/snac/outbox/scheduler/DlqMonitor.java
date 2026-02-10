package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.common.notification.SlackNotifier;
import com.ureca.snac.common.notification.dto.SlackAttachment;
import com.ureca.snac.common.notification.dto.SlackField;
import com.ureca.snac.common.notification.dto.SlackMessage;
import com.ureca.snac.config.RabbitMQQueue;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DLQ 모니터링 서비스
 * <p>
 * 1분마다 DLQ 메시지 개수 감시
 * 개수 변화 시에만 알림 (중복 방지)
 * 운영자에게 안내 가능
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlqMonitor {

    private final AmqpAdmin amqpAdmin;
    private final SlackNotifier slackNotifier;
    private final MeterRegistry meterRegistry;

    // 중복 알림 방지용 (큐별 마지막 알림 개수)
    private final Map<String, Integer> lastAlertedCount = new ConcurrentHashMap<>();
    // DLQ Gauge 값 저장용
    private final Map<String, AtomicInteger> dlqGaugeValues = new ConcurrentHashMap<>();

    // DLQ 모니터링 (1분마다)
    @Scheduled(fixedDelayString = "${outbox.dlq-monitor.interval}")
    public void monitorDlq() {
        try {
            checkAndAlert(RabbitMQQueue.MEMBER_JOINED_DLQ, "회원가입");
            checkAndAlert(RabbitMQQueue.WALLET_CREATED_DLQ, "지갑생성");
            checkAndAlert(RabbitMQQueue.PAYMENT_CANCEL_COMPENSATE_DLQ, "결제취소보상");

        } catch (Exception e) {
            log.error("[DLQ 모니터링] 실패", e);
        }
    }

    // DLQ Gauge 업데이트
    private void updateDlqGauge(String queueName, int count) {
        dlqGaugeValues.computeIfAbsent(queueName, key -> {
            AtomicInteger value = new AtomicInteger(0);
            Gauge.builder("dlq_messages_routed_total", value, AtomicInteger::get)
                    .tag("queue", key)
                    .register(meterRegistry);
            return value;
        }).set(count);
    }

    // 큐 확인 및 알림 -> 개수 변화 있을 때만 알림 전송
    private void checkAndAlert(String queueName, String domain) {
        Integer currentCount = getMessageCount(queueName);

        // Gauge 업데이트
        if (currentCount != null) {
            updateDlqGauge(queueName, currentCount);
        }

        Integer lastCount = lastAlertedCount.get(queueName);

        if (currentCount != null && currentCount > 0) {
            if (lastCount == null || !lastCount.equals(currentCount)) {
                // 개수 변화 -> 알림 전송
                log.warn("[DLQ 감지] queue: {}, count: {}", queueName, currentCount);

                SlackMessage message = buildSlackMessage(queueName, domain, currentCount);
                slackNotifier.sendAsync(message);

                lastAlertedCount.put(queueName, currentCount);
            } else {
                // 개수 동일 -> 알림 생략
                log.debug("[DLQ 알림 생략] queue: {}, count: {} (변화 없음)",
                        queueName, currentCount);
            }
        } else if (currentCount != null && currentCount == 0) {
            // 메시지 없으면 캐시 제거
            lastAlertedCount.remove(queueName);
        }
    }

    // Spring AMQP로 큐 메시지 개수 조회
    private Integer getMessageCount(String queueName) {
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(queueName);

            if (info != null) {
                return info.getMessageCount();
            }

            log.warn("[DLQ 모니터링] 큐 정보 없음. queueName: {}", queueName);
            return null;

        } catch (Exception e) {
            log.warn("[DLQ 모니터링] 큐 조회 실패. queueName: {}", queueName, e);
            return null;
        }
    }

    /**
     * Slack 메시지 생성 (Attachment 형식)
     * 운영자 즉시 액션 가능한 가이드 포함
     */
    private SlackMessage buildSlackMessage(String queueName, String domain, int count) {
        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        List<SlackField> fields = List.of(
                SlackField.longField("큐", queueName),
                SlackField.of("도메인", domain),
                SlackField.of("대기 메시지", count + "건"),
                SlackField.of("발생 시각", timestamp),
                SlackField.longField("조치",
                        """
                                1. RabbitMQ Management Console 접속
                                2. DLQ 메시지 원인 확인
                                3. 재처리 또는 삭제 결정
                                """
                )
        );

        SlackAttachment attachment = SlackAttachment.danger(fields);

        return SlackMessage.of("경고 DLQ 메시지 감지", attachment);
    }
}