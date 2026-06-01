package com.ureca.snac.wallet.service;

import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.dto.WalletAvailableBalanceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 낙관락
 * Wallet에 @Version 적용, 커밋 시점에 충돌 감지 -> OptimisticLockingFailureException 발생
 * Facade가 트랜잭션 경계 바깥에서 @Retryable로 재시도 -> 각 재시도가 새 트랜잭션에서 실행
 * DB 락 없음 (WalletServiceImpl이 findWallet 사용)
 * 재시도 횟수는 RETRY_COUNT static counter로 측정
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class OptimisticWalletFacade implements WalletService {

    public static final AtomicLong RETRY_COUNT = new AtomicLong();
    public static final AtomicLong FAIL_COUNT = new AtomicLong();

    private static final int MAX_ATTEMPTS = 5;
    private static final long BACKOFF_DELAY_MS = 30L;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    private final WalletServiceImpl delegate;

    public static void resetCounters() {
        RETRY_COUNT.set(0);
        FAIL_COUNT.set(0);
    }
    
    @Override
    public void createWallet(Long memberId) {
        delegate.createWallet(memberId);
    }

    @Override
    @Retryable(retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = BACKOFF_DELAY_MS, multiplier = BACKOFF_MULTIPLIER))
    public long depositMoney(Long memberId, long amount) {
        return invoke(() -> delegate.depositMoney(memberId, amount));
    }

    @Override
    @Retryable(retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = BACKOFF_DELAY_MS, multiplier = BACKOFF_MULTIPLIER))
    public long withdrawMoney(Long memberId, long amount) {
        return invoke(() -> delegate.withdrawMoney(memberId, amount));
    }

    @Override
    @Retryable(retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = BACKOFF_DELAY_MS, multiplier = BACKOFF_MULTIPLIER))
    public long depositPoint(Long memberId, long amount) {
        return invoke(() -> delegate.depositPoint(memberId, amount));
    }

    @Override
    @Retryable(retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = BACKOFF_DELAY_MS, multiplier = BACKOFF_MULTIPLIER))
    public CompositeBalanceResult moveCompositeToEscrow(Long memberId, long moneyAmount, long pointAmount) {
        return invoke(() -> delegate.moveCompositeToEscrow(memberId, moneyAmount, pointAmount));
    }

    @Override
    @Retryable(retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = BACKOFF_DELAY_MS, multiplier = BACKOFF_MULTIPLIER))
    public CompositeBalanceResult cancelCompositeEscrow(Long memberId, long moneyAmount, long pointAmount) {
        return invoke(() -> delegate.cancelCompositeEscrow(memberId, moneyAmount, pointAmount));
    }

    @Override
    @Retryable(retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = BACKOFF_DELAY_MS, multiplier = BACKOFF_MULTIPLIER))
    public CompositeBalanceResult deductCompositeEscrow(Long memberId, long moneyAmount, long pointAmount) {
        return invoke(() -> delegate.deductCompositeEscrow(memberId, moneyAmount, pointAmount));
    }

    @Override
    @Retryable(retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = BACKOFF_DELAY_MS, multiplier = BACKOFF_MULTIPLIER))
    public long freezeMoney(Long memberId, long amount) {
        return invoke(() -> delegate.freezeMoney(memberId, amount));
    }

    @Override
    @Retryable(retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = BACKOFF_DELAY_MS, multiplier = BACKOFF_MULTIPLIER))
    public long unfreezeMoney(Long memberId, long amount) {
        return invoke(() -> delegate.unfreezeMoney(memberId, amount));
    }

    @Override
    @Retryable(retryFor = OptimisticLockingFailureException.class,
            maxAttempts = MAX_ATTEMPTS,
            backoff = @Backoff(delay = BACKOFF_DELAY_MS, multiplier = BACKOFF_MULTIPLIER))
    public long deductFrozenMoney(Long memberId, long amount) {
        return invoke(() -> delegate.deductFrozenMoney(memberId, amount));
    }

    @Override
    public long getMoneyBalance(Long memberId) {
        return delegate.getMoneyBalance(memberId);
    }

    @Override
    public WalletAvailableBalanceResponse getWalletSummary(Long memberId) {
        return delegate.getWalletSummary(memberId);
    }

    private <T> T invoke(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (OptimisticLockingFailureException e) {
            RETRY_COUNT.incrementAndGet();
            log.debug("[낙관락] 충돌 감지. 재시도. 누적 retry={}", RETRY_COUNT.get());
            throw e;
        }
    }
}
