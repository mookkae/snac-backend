package com.ureca.snac.wallet.service;

import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.dto.WalletAvailableBalanceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JVM 락
 * per-member ReentrantLock으로 진입점 단위 직렬화
 * Facade로 트랜잭션 경계 바깥에서 락 잡아서 커밋 완료 후 unlock 보장
 * DB 락 없음 (WalletServiceImpl이 findWallet 사용)
 */
@Primary
@Service
@RequiredArgsConstructor
public class SynchronizedWalletFacade implements WalletService {

    private final WalletServiceImpl delegate;
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    private Lock getLock(Long memberId) {
        return locks.computeIfAbsent(memberId, k -> new ReentrantLock());
    }

    @Override
    public void createWallet(Long memberId) {
        delegate.createWallet(memberId);
    }

    @Override
    public long depositMoney(Long memberId, long amount) {
        Lock lock = getLock(memberId);
        lock.lock();
        try {
            return delegate.depositMoney(memberId, amount);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long withdrawMoney(Long memberId, long amount) {
        Lock lock = getLock(memberId);
        lock.lock();
        try {
            return delegate.withdrawMoney(memberId, amount);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long depositPoint(Long memberId, long amount) {
        Lock lock = getLock(memberId);
        lock.lock();
        try {
            return delegate.depositPoint(memberId, amount);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CompositeBalanceResult moveCompositeToEscrow(Long memberId, long moneyAmount, long pointAmount) {
        Lock lock = getLock(memberId);
        lock.lock();
        try {
            return delegate.moveCompositeToEscrow(memberId, moneyAmount, pointAmount);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CompositeBalanceResult cancelCompositeEscrow(Long memberId, long moneyAmount, long pointAmount) {
        Lock lock = getLock(memberId);
        lock.lock();
        try {
            return delegate.cancelCompositeEscrow(memberId, moneyAmount, pointAmount);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public CompositeBalanceResult deductCompositeEscrow(Long memberId, long moneyAmount, long pointAmount) {
        Lock lock = getLock(memberId);
        lock.lock();
        try {
            return delegate.deductCompositeEscrow(memberId, moneyAmount, pointAmount);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long freezeMoney(Long memberId, long amount) {
        Lock lock = getLock(memberId);
        lock.lock();
        try {
            return delegate.freezeMoney(memberId, amount);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long unfreezeMoney(Long memberId, long amount) {
        Lock lock = getLock(memberId);
        lock.lock();
        try {
            return delegate.unfreezeMoney(memberId, amount);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long deductFrozenMoney(Long memberId, long amount) {
        Lock lock = getLock(memberId);
        lock.lock();
        try {
            return delegate.deductFrozenMoney(memberId, amount);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long getMoneyBalance(Long memberId) {
        return delegate.getMoneyBalance(memberId);
    }

    @Override
    public WalletAvailableBalanceResponse getWalletSummary(Long memberId) {
        return delegate.getWalletSummary(memberId);
    }
}
