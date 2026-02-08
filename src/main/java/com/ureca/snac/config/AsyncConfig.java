package com.ureca.snac.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 비동기 이벤트 처리를 위한 Executor 설정
 * <p>
 * Executor 분리 전략
 * Outbox 이벤트 발행 AFTER_COMMIT 리스너가 사용
 * Slack 알림
 * TossPaymentsAdapter, MoneyDepositor 재시도 지원
 */
@Slf4j
@Configuration
@EnableAsync
@EnableRetry
@EnableConfigurationProperties(RetryProperties.class)
public class AsyncConfig {
    public static final String EVENT_EXECUTOR_NAME = "eventAsyncExecutor";
    public static final String NOTIFICATION_EXECUTOR_NAME = "notificationAsyncExecutor";

    /**
     * Outbox 이벤트 발행 전용 Executor
     * <p>
     * 높은 우선순위의 비즈니스 로직
     * 큐가 가득차면 쓰레드 직접 실행
     * 이벤트 발행 실패 방지
     * 우아한 종료 : 배포 시 전송 중이던 이벤트 대기 10초 유예시간 부여
     */
    @Bean(name = EVENT_EXECUTOR_NAME)
    public Executor eventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(5);           // 일단 5개
        executor.setMaxPoolSize(10);           // 최대 10개
        executor.setQueueCapacity(100);        // 큐는 100개
        executor.setThreadNamePrefix("Event-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Graceful Shutdown 설정 - 29cm 차용
        executor.setWaitForTasksToCompleteOnShutdown(true);  // 작업 완료 대기
        executor.setAwaitTerminationSeconds(10);             // 최대 10초까지 대기

        executor.initialize();

        log.info("[비동기] Event Executor 초기화 완료");
        return executor;
    }

    /**
     * Slack 알림 전용 Executor
     * <p>
     * 낮은 우선순위 의 부가기능과 알림 실패해도 비즈니스 영향 없어서
     * 큐의 사이즈도 줄이고 외부 API 호출로 시간 불확실을 독립적으로 실행
     */
    @Bean(name = NOTIFICATION_EXECUTOR_NAME)
    public Executor notificationAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);           // 일단 2개
        executor.setMaxPoolSize(5);           // 최대 5개
        executor.setQueueCapacity(50);        // 큐는 50개
        executor.setThreadNamePrefix("Notification-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());

        // Graceful Shutdown 설정 - 29cm 차용
        executor.setWaitForTasksToCompleteOnShutdown(true);  // 작업 완료 대기
        executor.setAwaitTerminationSeconds(10);             // 최대 10초까지 대기

        executor.initialize();

        log.info("[비동기] Slack Executor 초기화 완료");
        return executor;
    }
}