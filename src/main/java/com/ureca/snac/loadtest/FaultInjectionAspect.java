package com.ureca.snac.loadtest;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 부하 테스트 전용 장애 주입 Aspect
 * <p>
 * MoneyDepositor.deposit() 에 확률적 예외를 발생시켜
 * "Toss 승인 성공 + DB 실패" 시나리오를 유발한다.
 * <p>
 * deposit-failure-type
 * runtime: RuntimeException -> @Retryable 매칭 안 됨, 즉시 Auto-Cancel chain (보상 검증)
 * transient: TransientDataAccessResourceException -> @Retryable 트리거 (재시도 검증)
 * <p>
 * 매 @Around 실행마다 독립 dice -? per-attempt independent.
 */
@Slf4j
@Aspect
@Component
@Profile("loadtest")
public class FaultInjectionAspect {

    @Value("${loadtest.fault.deposit-failure-rate}")
    private double depositFailureRate;

    @Value("${loadtest.fault.deposit-failure-type:runtime}")
    private String depositFailureType;

    @Around("execution(* com.ureca.snac.money.service.MoneyDepositor.deposit(..))")
    public Object injectDepositFault(ProceedingJoinPoint joinPoint) throws Throwable {
        if (ThreadLocalRandom.current().nextDouble() < depositFailureRate) {
            log.warn("[LoadTest 장애 주입] MoneyDepositor.deposit() 예외 발생. type: {}", depositFailureType);
            if ("transient".equalsIgnoreCase(depositFailureType)) {
                throw new TransientDataAccessResourceException(
                        "[LoadTest] Injected transient DB failure for retry test");
            }
            throw new RuntimeException("[LoadTest] Injected deposit failure for compensation test");
        }
        return joinPoint.proceed();
    }
}
