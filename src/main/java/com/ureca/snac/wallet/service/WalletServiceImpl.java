package com.ureca.snac.wallet.service;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.wallet.repository.WalletRepository;
import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.dto.WalletSummaryResponse;
import com.ureca.snac.wallet.entity.Wallet;
import com.ureca.snac.wallet.event.WalletCreatedEvent;
import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import com.ureca.snac.wallet.exception.WalletNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public void createWallet(Member member) {
        log.info("[지갑생성] createWallet 진입. 회원 ID : {}, 이메일 : {}",
                member.getId(), member.getEmail());

        if (walletRepository.existsByMemberId(member.getId())) {
            log.info("[지갑 생성] 이미 존재. 중복 처리 방지. 회원 ID: {}", member.getId());
            return;
        }

        Wallet wallet = Wallet.create(member);
        walletRepository.save(wallet);

        log.info("[지갑 생성] 생성 완료. 지갑 ID : {}, 회원 ID : {}", wallet.getId(), member.getId());

        // Outbox 패턴 이벤트 발행
        publishWalletCreatedEvent(wallet, member);
    }

    /**
     * 지갑 생성 이벤트 발행 (Outbox 패턴)
     * OutboxEventListener가 자동으로 Outbox 테이블에 저장
     * Hybrid Push로 즉시 발행 안되면 이후에 스케줄러가 처리
     */
    private void publishWalletCreatedEvent(Wallet wallet, Member member) {
        log.info("[이벤트 발행] 지갑 생성 이벤트 발행. 지갑 ID: {}, 회원 ID: {}",
                wallet.getId(), member.getId());

        eventPublisher.publishEvent(
                new WalletCreatedEvent(wallet.getId(), member.getId())
        );
    }

    @Override
    @Transactional
    public Long depositMoney(Long memberId, long amount) {
        log.info("[머니 입금] 시작. 회원 ID : {}, 입금액 : {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.depositMoney(amount);

        long finalBalance = wallet.getMoneyBalance();
        log.info("[머니 입금] 완료. 회원 ID : {}, 최종 머니 잔액 : {}", memberId, finalBalance);

        return finalBalance;
    }

    @Override
    @Transactional
    public Long withdrawMoney(Long memberId, long amount) {
        log.info("[머니 출금] 시작. 회원 ID: {}, 출금액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.withdrawMoney(amount);

        long finalBalance = wallet.getMoneyBalance();
        log.info("[머니 출금] 완료. 회원 ID: {}, 최종 잔액: {}", memberId, finalBalance);

        return finalBalance;
    }

    @Override
    @Transactional
    public long moveMoneyToEscrow(Long memberId, long amount) {
        log.info("[머니 에스크로 이동] 시작. 회원 ID: {}, 금액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.moveMoneyToEscrow(amount);

        long finalBalance = wallet.getMoneyBalance();
        log.info("[머니 에스크로 이동] 완료. 회원 ID: {}, 잔액: {}, 에스크로: {}",
                memberId, finalBalance, wallet.getMoneyEscrow());

        return finalBalance;
    }

    @Override
    @Transactional
    public long releaseMoneyEscrow(Long memberId, long amount) {
        log.info("[머니 에스크로 복원] 시작. 회원 ID: {}, 금액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.releaseMoneyEscrow(amount);

        long finalBalance = wallet.getMoneyBalance();
        log.info("[머니 에스크로 복원] 완료. 회원 ID: {}, 잔액: {}, 에스크로: {}",
                memberId, finalBalance, wallet.getMoneyEscrow());

        return finalBalance;
    }

    @Override
    @Transactional
    public long deductMoneyEscrow(Long memberId, long amount) {
        log.info("[머니 에스크로 차감] 시작. 회원 ID: {}, 금액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.deductMoneyEscrow(amount);

        long finalEscrow = wallet.getMoneyEscrow();
        log.info("[머니 에스크로 차감] 완료. 회원 ID: {}, 에스크로: {}", memberId, finalEscrow);

        return wallet.getMoneyBalance();
    }

    @Override
    @Transactional
    public Long depositPoint(Long memberId, long amount) {
        log.info("[포인트 적립] 시작. 회원 ID: {}, 적립액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.depositPoint(amount);

        long finalBalance = wallet.getPointBalance();
        log.info("[포인트 적립] 완료. 회원 ID: {}, 최종 잔액: {}", memberId, finalBalance);

        return finalBalance;

    }

    @Override
    @Transactional
    public Long withdrawPoint(Long memberId, long amount) {
        log.info("[포인트 사용] 시작. 회원 ID: {}, 사용액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.withdrawPoint(amount);

        long finalBalance = wallet.getPointBalance();
        log.info("[포인트 사용] 완료. 회원 ID: {}, 최종 잔액: {}", memberId, finalBalance);

        return finalBalance;
    }

    @Override
    @Transactional
    public long movePointToEscrow(Long memberId, long amount) {
        log.info("[포인트 에스크로 이동] 시작. 회원 ID: {}, 금액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.movePointToEscrow(amount);

        long finalBalance = wallet.getPointBalance();
        log.info("[포인트 에스크로 이동] 완료. 회원 ID: {}, 잔액: {}, 에스크로: {}",
                memberId, finalBalance, wallet.getPointEscrow());

        return finalBalance;
    }

    @Override
    @Transactional
    public long releasePointEscrow(Long memberId, long amount) {
        log.info("[포인트 에스크로 복원] 시작. 회원 ID: {}, 금액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.releasePointEscrow(amount);

        long finalBalance = wallet.getPointBalance();
        log.info("[포인트 에스크로 복원] 완료. 회원 ID: {}, 잔액: {}, 에스크로: {}",
                memberId, finalBalance, wallet.getPointEscrow());

        return finalBalance;
    }

    @Override
    @Transactional
    public long deductPointEscrow(Long memberId, long amount) {
        log.info("[포인트 에스크로 차감] 시작. 회원 ID: {}, 금액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.deductPointEscrow(amount);

        long finalEscrow = wallet.getPointEscrow();
        log.info("[포인트 에스크로 차감] 완료. 회원 ID: {}, 에스크로: {}", memberId, finalEscrow);

        return wallet.getPointBalance();
    }

    @Override
    @Transactional
    public CompositeBalanceResult withdrawComposite(Long memberId, long moneyAmount, long pointAmount) {
        if (moneyAmount <= 0 && pointAmount <= 0) {
            throw new IllegalArgumentException("출금 금액은 0보다 커야 합니다.");
        }

        log.info("[복합 출금] 시작. 회원 ID: {}, 머니: {}, 포인트: {}", memberId, moneyAmount, pointAmount);

        Wallet wallet = findWalletWithLock(memberId);

        if (moneyAmount > 0) {
            wallet.withdrawMoney(moneyAmount);
        }
        if (pointAmount > 0) {
            wallet.withdrawPoint(pointAmount);
        }

        CompositeBalanceResult result = CompositeBalanceResult.from(wallet);
        log.info("[복합 출금] 완료. 회원 ID: {}, 머니 잔액: {}, 포인트 잔액: {}",
                memberId, result.moneyBalance(), result.pointBalance());

        return result;
    }

    @Override
    @Transactional
    public CompositeBalanceResult moveCompositeToEscrow(Long memberId, long moneyAmount, long pointAmount) {
        if (moneyAmount <= 0 && pointAmount <= 0) {
            throw new InsufficientBalanceException();
        }

        log.info("[복합 에스크로 이동] 시작. 회원 ID: {}, 머니: {}, 포인트: {}", memberId, moneyAmount, pointAmount);

        Wallet wallet = findWalletWithLock(memberId);

        if (moneyAmount > 0) {
            wallet.moveMoneyToEscrow(moneyAmount);
        }
        if (pointAmount > 0) {
            wallet.movePointToEscrow(pointAmount);
        }

        CompositeBalanceResult result = CompositeBalanceResult.from(wallet);
        log.info("[복합 에스크로 이동] 완료. 회원 ID: {}, 머니 에스크로: {}, 포인트 에스크로: {}",
                memberId, result.moneyEscrow(), result.pointEscrow());

        return result;
    }

    @Override
    @Transactional
    public CompositeBalanceResult releaseCompositeEscrow(Long memberId, long moneyAmount, long pointAmount) {
        if (moneyAmount <= 0 && pointAmount <= 0) {
            throw new InsufficientBalanceException();
        }

        log.info("[복합 에스크로 복원] 시작. 회원 ID: {}, 머니: {}, 포인트: {}", memberId, moneyAmount, pointAmount);

        Wallet wallet = findWalletWithLock(memberId);

        if (moneyAmount > 0) {
            wallet.releaseMoneyEscrow(moneyAmount);
        }
        if (pointAmount > 0) {
            wallet.releasePointEscrow(pointAmount);
        }

        CompositeBalanceResult result = CompositeBalanceResult.from(wallet);
        log.info("[복합 에스크로 복원] 완료. 회원 ID: {}, 머니 잔액: {}, 포인트 잔액: {}",
                memberId, result.moneyBalance(), result.pointBalance());

        return result;
    }

    @Override
    @Transactional
    public CompositeBalanceResult deductCompositeEscrow(Long memberId, long moneyAmount, long pointAmount) {
        if (moneyAmount <= 0 && pointAmount <= 0) {
            throw new InsufficientBalanceException();
        }

        log.info("[복합 에스크로 차감] 시작. 회원 ID : {}, 머니 : {}, 포인트 : {}", memberId, moneyAmount, pointAmount);

        Wallet wallet = findWalletWithLock(memberId);

        if (moneyAmount > 0) {
            wallet.deductMoneyEscrow(moneyAmount);
        }
        if (pointAmount > 0) {
            wallet.deductPointEscrow(pointAmount);
        }

        CompositeBalanceResult result = CompositeBalanceResult.from(wallet);
        log.info("[복합 에스크로 차감] 완료. 회원 ID: {}, 머니 에스크로: {}, 포인트 에스크로: {}",
                memberId, result.moneyEscrow(), result.pointEscrow());

        return result;
    }

    @Override
    public long getMoneyBalance(Long memberId) {
        Wallet wallet = findWallet(memberId);
        return wallet.getMoneyBalance();
    }

    @Override
    public long getMoneyEscrow(Long memberId) {
        Wallet wallet = findWallet(memberId);
        return wallet.getMoneyEscrow();
    }

    @Override
    public long getPointBalance(Long memberId) {
        Wallet wallet = findWallet(memberId);
        return wallet.getPointBalance();
    }

    @Override
    public long getPointEscrow(Long memberId) {
        Wallet wallet = findWallet(memberId);
        return wallet.getPointEscrow();
    }


    @Override
    public WalletSummaryResponse getWalletSummary(String email) {
        log.info("[지갑 요약 조회] 시작. 이메일 : {}", email);

        Wallet wallet = walletRepository.findByMemberEmail(email)
                .orElseThrow(() -> {
                    log.error("[지갑 요약 조회] 실패 . 지갑을 찾을 수 없습니다 이메일 : {}", email);
                    return new WalletNotFoundException();
                });

        WalletSummaryResponse response = WalletSummaryResponse.from(wallet);
        log.info("[지갑 요약 조회] 완료. 이메일 : {}", email);

        return response;
    }

    private Wallet findWallet(Long memberId) {
        return walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> {
                    log.error("[지갑 조회] 지갑을 찾을 수 없음. 회원 ID : {}", memberId);
                    return new WalletNotFoundException();
                });
    }

    private Wallet findWalletWithLock(Long memberId) {
        log.debug("[지갑 조회] 비관적 락 사용. 회원 ID : {}", memberId);

        return walletRepository.findByMemberIdWithLock(memberId)
                .orElseThrow(() -> {
                    log.error("[지갑 조회] 지갑을 찾을 수 없음. 회원 ID : {}", memberId);
                    return new WalletNotFoundException();
                });
    }
}