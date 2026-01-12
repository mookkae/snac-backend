package com.ureca.snac.common.notification.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

// 키 밸류 쌍
public record SlackField(
        String title,
        String value,
        @JsonProperty("short") boolean shortField
) {

    public static SlackField of(String title, String value) {
        return new SlackField(title, value, true);
    }

    public static SlackField longField(String title, String value) {
        return new SlackField(title, value, false);
    }
}
