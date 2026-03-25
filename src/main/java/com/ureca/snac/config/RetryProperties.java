package com.ureca.snac.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "retry")
public record RetryProperties(
        RetryPolicy toss,
        RetryPolicy depositor,
        RetryPolicy slack,
        RetryPolicy trade,
        RetryPolicy settlement
) {
    public record RetryPolicy(
            int maxAttempts,
            long delay,
            double multiplier
    ) {
    }
}
