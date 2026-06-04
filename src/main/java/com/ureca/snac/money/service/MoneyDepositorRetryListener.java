package com.ureca.snac.money.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * MoneyDepositorRetryFacade.deposit() 시도별 메트릭
 * <p>
 * retry_attempt_outcome_total{attempt, result} : 시도별 성공·실패 카운트
 * retry_total_duration{outcome} : 백오프 포함 누적 시간
 */
@Component("moneyDepositorRetryListener")
@RequiredArgsConstructor
public class MoneyDepositorRetryListener implements RetryListener {

    private static final String START_NANOS_KEY = "moneyDepositor.startNanos";

    private final MeterRegistry meterRegistry;

    @Override
    public <T, E extends Throwable> boolean open(RetryContext context, RetryCallback<T, E> callback) {
        context.setAttribute(START_NANOS_KEY, System.nanoTime());
        return true;
    }

    @Override
    public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        int failedAttempt = context.getRetryCount();
        meterRegistry.counter("retry_attempt_outcome_total",
                        "attempt", String.valueOf(failedAttempt),
                        "result", "fail")
                .increment();
    }

    @Override
    public <T, E extends Throwable> void close(RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
        Object start = context.getAttribute(START_NANOS_KEY);
        if (start instanceof Long startNanos) {
            long durationNanos = System.nanoTime() - startNanos;
            meterRegistry.timer("retry_total_duration", "outcome", throwable == null ? "success" : "exhausted")
                    .record(durationNanos, TimeUnit.NANOSECONDS);
        }

        if (throwable == null) {
            int successAttempt = context.getRetryCount() + 1;
            meterRegistry.counter("retry_attempt_outcome_total",
                            "attempt", String.valueOf(successAttempt),
                            "result", "success")
                    .increment();
        }
    }
}