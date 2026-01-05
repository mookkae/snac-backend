package com.ureca.snac.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 비동기 이벤트 처리를 위한 Executor 설정
 * Hybrid Push 전략에서 AFTER_COMMIT 리스너가 사용
 */
@Configuration
@EnableAsync
public class AsyncConfig {
    public static final String EVENT_EXECUTOR_NAME = "eventExecutor";

    @Bean(name = EVENT_EXECUTOR_NAME)
    public Executor eventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(5);           // 일단 5개
        executor.setMaxPoolSize(10);           // 최대 10개
        executor.setQueueCapacity(100);        // 큐는 100개
        executor.setThreadNamePrefix("event-async-");

        // Graceful Shutdown 설정 - 29cm 차용
        executor.setWaitForTasksToCompleteOnShutdown(true);  // 작업 완료 대기
        executor.setAwaitTerminationSeconds(10);             // 최대 10초까지 대기

        executor.initialize();
        return executor;
    }
}