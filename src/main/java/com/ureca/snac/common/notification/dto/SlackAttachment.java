package com.ureca.snac.common.notification.dto;

import java.util.List;

// 구조화 된 메시지
public record SlackAttachment(
        String color,
        List<SlackField> fields,
        String footer,
        Long ts // Time stamp
) {
    public static SlackAttachment danger(List<SlackField> fields) {
        return new SlackAttachment(
                "danger",
                fields,
                "snac backend monitoring",
                System.currentTimeMillis() / 1000
        );
    }

    public static SlackAttachment warning(List<SlackField> fields) {
        return new SlackAttachment(
                "warning",
                fields,
                "snac backend monitoring",
                System.currentTimeMillis() / 1000
        );
    }
}
