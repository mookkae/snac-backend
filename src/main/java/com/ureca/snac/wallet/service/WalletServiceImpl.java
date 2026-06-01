package com.ureca.snac.wallet.service;

import com.ureca.snac.common.metric.TransactionAwareMetricRecorder;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.dto.WalletAvailableBalanceResponse;
import com.ureca.snac.wallet.entity.Wallet;
import com.ureca.snac.wallet.event.WalletCreatedEvent;
import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import com.ureca.snac.wallet.exception.InvalidAmountException;
import com.ureca.snac.wallet.exception.WalletNotFoundException;
import com.ureca.snac.wallet.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CAS: Atomic SQL UPDATE 기반
 * 모든 write 경로가 단일 UPDATE 문으로 잔액 검증 + 갱신을 원자적으로 처리
 * rows affected = 0 -> 잔액 부족 (잔액 검증 WHERE 절 실패)
 * SELECT FOR UPDATE 없음 InnoDB row 락은 UPDATE 실행 순간만 보유
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final MemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionAwareMetricRecorder metricRecorder;

    @Override
    @Transactional
    public void createWallet(Long memberId) {
        log.info("[지갑 생성] createWallet 진입. 회원 ID : {}", memberId);

        if (walletRepository.existsByMemberId(memberId)) {
            log.info("[지갑 생성] 이미 존재. 중복 처리 방지. 회원 ID: {}", memberId);
            return;
        }

        Member member = memberRepository.findById(memberId)
                .orElseThrow(MemberNotFoundException::new);

        Wallet wallet = Wallet.create(member);
        walletRepository.save(wallet);

        eventPublisher.publishEvent(new WalletCreatedEvent(wallet.getId(), memberId));
    }

    @Override
    @Transactional
    public long depositMoney(Long memberId, long amount) {
        validatePositive(amount);
        int affected = walletRepository.casDepositMoney(memberId, amount);
        if (affected == 0) {
            throw new WalletNotFoundException();
        }
        incrementCounter("deposit_money");
        return getMoneyBalance(memberId);
    }

    @Override
    @Transactional
    public long withdrawMoney(Long memberId, long amount) {
        validatePositive(amount);
        int affected = walletRepository.casWithdrawMoney(memberId, amount);
        if (affected == 0) {
            throw new InsufficientBalanceException();
        }
        incrementCounter("withdraw_money");
        return getMoneyBalance(memberId);
    }

    @Override
    @Transactional
    public long depositPoint(Long memberId, long amount) {
        validatePositive(amount);
        int affected = walletRepository.casDepositPoint(memberId, amount);
        if (affected == 0) {
            throw new WalletNotFoundException();
        }
        incrementCounter("deposit_point");
        return findWallet(memberId).getPointBalance();
    }

    @Override
    @Transactional
    public CompositeBalanceResult moveCompositeToEscrow(Long memberId, long moneyAmount, long pointAmount) {
        validateCompositeAmounts(moneyAmount, pointAmount);
        if (moneyAmount > 0) {
            if (walletRepository.casMoveMoneyToEscrow(memberId, moneyAmount) == 0) {
                throw new InsufficientBalanceException();
            }
        }
        if (pointAmount > 0) {
            if (walletRepository.casMovePointToEscrow(memberId, pointAmount) == 0) {
                throw new InsufficientBalanceException();
            }
        }
        incrementCounter("move_composite_escrow");
        return CompositeBalanceResult.from(findWallet(memberId));
    }

    @Override
    @Transactional
    public CompositeBalanceResult cancelCompositeEscrow(Long memberId, long moneyAmount, long pointAmount) {
        validateCompositeAmounts(moneyAmount, pointAmount);
        if (moneyAmount > 0) {
            if (walletRepository.casCancelMoneyEscrow(memberId, moneyAmount) == 0) {
                throw new InsufficientBalanceException();
            }
        }
        if (pointAmount > 0) {
            if (walletRepository.casCancelPointEscrow(memberId, pointAmount) == 0) {
                throw new InsufficientBalanceException();
            }
        }
        incrementCounter("cancel_composite_escrow");
        return CompositeBalanceResult.from(findWallet(memberId));
    }

    @Override
    @Transactional
    public CompositeBalanceResult deductCompositeEscrow(Long memberId, long moneyAmount, long pointAmount) {
        validateCompositeAmounts(moneyAmount, pointAmount);
        if (moneyAmount > 0) {
            if (walletRepository.casDeductMoneyEscrow(memberId, moneyAmount) == 0) {
                throw new InsufficientBalanceException();
            }
        }
        if (pointAmount > 0) {
            if (walletRepository.casDeductPointEscrow(memberId, pointAmount) == 0) {
                throw new InsufficientBalanceException();
            }
        }
        incrementCounter("deduct_composite_escrow");
        return CompositeBalanceResult.from(findWallet(memberId));
    }

    @Override
    @Transactional
    public long freezeMoney(Long memberId, long amount) {
        validatePositive(amount);
        int affected = walletRepository.casFreezeMoney(memberId, amount);
        if (affected == 0) {
            throw new InsufficientBalanceException();
        }
        incrementCounter("freeze_money");
        return getMoneyBalance(memberId);
    }

    @Override
    @Transactional
    public long unfreezeMoney(Long memberId, long amount) {
        validatePositive(amount);
        int affected = walletRepository.casUnfreezeMoney(memberId, amount);
        if (affected == 0) {
            throw new InsufficientBalanceException();
        }
        incrementCounter("unfreeze_money");
        return getMoneyBalance(memberId);
    }

    @Override
    @Transactional
    public long deductFrozenMoney(Long memberId, long amount) {
        validatePositive(amount);
        int affected = walletRepository.casDeductFrozenMoney(memberId, amount);
        if (affected == 0) {
            throw new InsufficientBalanceException();
        }
        incrementCounter("deduct_frozen_money");
        return getMoneyBalance(memberId);
    }

    @Override
    public long getMoneyBalance(Long memberId) {
        return findWallet(memberId).getMoneyBalance();
    }

    @Override
    public WalletAvailableBalanceResponse getWalletSummary(Long memberId) {
        return WalletAvailableBalanceResponse.from(findWallet(memberId));
    }

    private void incrementCounter(String type) {
        metricRecorder.increment("wallet_operation_total", "type", type);
    }

    private Wallet findWallet(Long memberId) {
        return walletRepository.findByMemberId(memberId)
                .orElseThrow(WalletNotFoundException::new);
    }

    private void validatePositive(long amount) {
        if (amount <= 0) {
            throw new InvalidAmountException();
        }
    }

    private void validateCompositeAmounts(long moneyAmount, long pointAmount) {
        if (moneyAmount < 0 || pointAmount < 0 || (moneyAmount == 0 && pointAmount == 0)) {
            throw new InvalidAmountException();
        }
    }
}
