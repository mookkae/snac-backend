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
 */
@Service
@RequiredArgsConstructor
public class MoneyDepositorRetryFacade {

    private final MoneyDepositor moneyDepositor;

    @Retryable(
            retryFor = {TransientDataAccessException.class},
            maxAttemptsExpression = "${retry.depositor.max-attempts}",
            backoff = @Backoff(delayExpression = "${retry.depositor.delay}",
                    multiplierExpression = "${retry.depositor.multiplier}")
    )
    public void deposit(Long paymentId, Long memberId, PaymentConfirmResult confirmResult) {
        moneyDepositor.deposit(paymentId, memberId, confirmResult);
    }
}
