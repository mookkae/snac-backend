package com.ureca.snac.loadtest;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 부하 테스트 전용 장애 주입 Aspect
 * <p>
 * MoneyDepositor.deposit() 에 확률적 예외를 발생시켜
 * "Toss 승인 성공 + DB 실패" 시나리오를 유발한다.
 * <p>
 * RuntimeException 을 던지므로 @Retryable(retryFor = DataAccessException) 에 해당하지 않아
 * 재시도 없이 즉시 Auto-Cancel chain 이 발동한다.
 */
@Slf4j
@Aspect
@Component
@Profile("loadtest")
public class FaultInjectionAspect {

    @Value("${loadtest.fault.deposit-failure-rate}")
    private double depositFailureRate;

    @Around("execution(* com.ureca.snac.money.service.MoneyDepositor.deposit(..))")
    public Object injectDepositFault(ProceedingJoinPoint joinPoint) throws Throwable {
        if (ThreadLocalRandom.current().nextDouble() < depositFailureRate) {
            log.warn("[LoadTest 장애 주입] MoneyDepositor.deposit() 예외 발생");
            throw new RuntimeException("[LoadTest] Injected deposit failure for compensation test");
        }
        return joinPoint.proceed();
    }
}
