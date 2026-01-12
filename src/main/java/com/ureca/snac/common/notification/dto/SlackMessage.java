package com.ureca.snac.common.notification.dto;

import java.util.List;

/**
 * Slack 메시지 DTO
 * Webhook 요청 본문 구조
 */
public record SlackMessage(
        String text,
        List<SlackAttachment> attachments
) {
    public static SlackMessage of(String text, SlackAttachment attachment) {
        return new SlackMessage(text, List.of(attachment));
    }
}
