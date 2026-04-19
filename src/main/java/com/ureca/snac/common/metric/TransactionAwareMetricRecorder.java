package com.ureca.snac.common.metric;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션 커밋 후에만 메트릭을 기록하는 컴포넌트.
 * 트랜잭션 롤백 시 메트릭이 오염되는 문제를 방지한다.
 */
@Component
@RequiredArgsConstructor
public class TransactionAwareMetricRecorder {

    private final MeterRegistry meterRegistry;

    public void increment(String name, String... tags) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    meterRegistry.counter(name, tags).increment();
                }
            });
        } else {
            // 트랜잭션이 없는 경우 즉시 증가
            meterRegistry.counter(name, tags).increment();
        }
    }
}
