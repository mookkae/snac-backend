package com.ureca.snac.infra.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;

import static java.util.Objects.requireNonNull;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TossConfirmResponse(
        String paymentKey,
        String method,
        OffsetDateTime approvedAt) {

    public TossConfirmResponse {
        requireNonNull(paymentKey, "paymentKey must not be null");
        requireNonNull(method, "method must not be null");
        requireNonNull(approvedAt, "approvedAt must not be null");
    }
}
