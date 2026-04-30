package com.ureca.snac.money.service;

import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

/**
 * MoneyDepositor의 @Retryable 래퍼
 * <p>
 *
 * @Retryable과 @Transactional을 동일 빈에 선언하면 AOP 프록시 순서가 보장되지 않아
 * TransientDataAccessException 발생 시 트랜잭션이 rollback-only로 마킹된 채 재시도
 * UnexpectedRollbackException 분리된 빈으로 @Retryable을 적용하여 각 재시도가 새 트랜잭션에서 실행되도록 보장
 * <p>
 * ObjectOptimisticLockingFailureException은 MoneyDepositor.deposit() 커밋 시점에 발생하므로
 * WalletServiceImpl.depositMoney()의 @Retryable에서는 잡을 수 없음.
 * Facade 레벨에서 잡아야 각 재시도가 새 트랜잭션에서 실행됨.
 */
@Service
@RequiredArgsConstructor
public class MoneyDepositorRetryFacade {

    private final MoneyDepositor moneyDepositor;

    @Retryable(
            // [낙관락]
            // MoneyDepositor.deposit() 커밋 시점에 예외 발생
            // depositMoney()의 @Retryable은 외부 트랜잭션 합류로 동작 안 하므로 Facade 레벨에서 처리
            // retryFor = {TransientDataAccessException.class, ObjectOptimisticLockingFailureException.class},
            // listeners = "walletRetryListener",
            retryFor = {TransientDataAccessException.class},
            maxAttemptsExpression = "${retry.depositor.max-attempts:5}",
            backoff = @Backoff(delayExpression = "${retry.depositor.delay:50}",
                    multiplierExpression = "${retry.depositor.multiplier:2.0}")
    )
    public void deposit(Long paymentId, Long memberId, PaymentConfirmResult confirmResult) {
        moneyDepositor.deposit(paymentId, memberId, confirmResult);
    }
}
