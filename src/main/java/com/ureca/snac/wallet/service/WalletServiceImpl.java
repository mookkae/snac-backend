package com.ureca.snac.wallet.service;

import com.ureca.snac.common.metric.TransactionAwareMetricRecorder;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.dto.WalletAvailableBalanceResponse;
import com.ureca.snac.wallet.entity.Wallet;
import com.ureca.snac.wallet.event.WalletCreatedEvent;
import com.ureca.snac.wallet.exception.WalletNotFoundException;
import com.ureca.snac.wallet.repository.WalletRepository;
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
                .orElseThrow(() -> {
                    log.error("[지갑 생성] 회원 조회 실패. 회원 ID: {}", memberId);
                    return new MemberNotFoundException();
                });

        Wallet wallet = Wallet.create(member);
        walletRepository.save(wallet);

        log.info("[지갑 생성] 생성 완료. 지갑 ID : {}, 회원 ID : {}", wallet.getId(), memberId);

        publishWalletCreatedEvent(wallet.getId(), memberId);
    }

    /**
     * 지갑 생성 이벤트 발행 (Outbox 패턴)
     * OutboxEventListener가 자동으로 Outbox 테이블에 저장
     * Hybrid Push로 즉시 발행 안되면 이후에 스케줄러가 처리
     */
    private void publishWalletCreatedEvent(Long walletId, Long memberId) {
        log.info("[이벤트 발행] 지갑 생성 이벤트 발행. 지갑 ID: {}, 회원 ID: {}", walletId, memberId);

        eventPublisher.publishEvent(new WalletCreatedEvent(walletId, memberId));
    }

    @Override
    @Transactional
    public long depositMoney(Long memberId, long amount) {
        log.info("[머니 입금] 시작. 회원 ID : {}, 입금액 : {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.depositMoney(amount);

        long finalBalance = wallet.getMoneyBalance();
        log.info("[머니 입금] 완료. 회원 ID : {}, 최종 머니 잔액 : {}", memberId, finalBalance);
        incrementCounter("deposit_money");

        return finalBalance;
    }

    @Override
    @Transactional
    public long withdrawMoney(Long memberId, long amount) {
        log.info("[머니 출금] 시작. 회원 ID: {}, 출금액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.withdrawMoney(amount);

        long finalBalance = wallet.getMoneyBalance();
        log.info("[머니 출금] 완료. 회원 ID: {}, 최종 잔액: {}", memberId, finalBalance);
        incrementCounter("withdraw_money");

        return finalBalance;
    }

    @Override
    @Transactional
    public long depositPoint(Long memberId, long amount) {
        log.info("[포인트 적립] 시작. 회원 ID: {}, 적립액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.depositPoint(amount);

        long finalBalance = wallet.getPointBalance();
        log.info("[포인트 적립] 완료. 회원 ID: {}, 최종 잔액: {}", memberId, finalBalance);
        incrementCounter("deposit_point");

        return finalBalance;
    }

    @Override
    @Transactional
    public CompositeBalanceResult moveCompositeToEscrow(Long memberId, long moneyAmount, long pointAmount) {
        log.info("[복합 에스크로 이동] 시작. 회원 ID: {}, 머니: {}, 포인트: {}", memberId, moneyAmount, pointAmount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.moveCompositeToEscrow(moneyAmount, pointAmount);

        CompositeBalanceResult result = CompositeBalanceResult.from(wallet);
        log.info("[복합 에스크로 이동] 완료. 회원 ID: {}, 머니 에스크로: {}, 포인트 에스크로: {}",
                memberId, result.moneyEscrow(), result.pointEscrow());
        incrementCounter("move_composite_escrow");

        return result;
    }

    @Override
    @Transactional
    public CompositeBalanceResult cancelCompositeEscrow(Long memberId, long moneyAmount, long pointAmount) {
        log.info("[복합 에스크로 복원] 시작. 회원 ID: {}, 머니: {}, 포인트: {}", memberId, moneyAmount, pointAmount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.cancelCompositeEscrow(moneyAmount, pointAmount);

        CompositeBalanceResult result = CompositeBalanceResult.from(wallet);
        log.info("[복합 에스크로 복원] 완료. 회원 ID: {}, 머니 잔액: {}, 포인트 잔액: {}",
                memberId, result.moneyBalance(), result.pointBalance());
        incrementCounter("cancel_composite_escrow");

        return result;
    }

    @Override
    @Transactional
    public CompositeBalanceResult deductCompositeEscrow(Long memberId, long moneyAmount, long pointAmount) {
        log.info("[복합 에스크로 차감] 시작. 회원 ID : {}, 머니 : {}, 포인트 : {}", memberId, moneyAmount, pointAmount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.deductCompositeEscrow(moneyAmount, pointAmount);

        CompositeBalanceResult result = CompositeBalanceResult.from(wallet);
        log.info("[복합 에스크로 차감] 완료. 회원 ID: {}, 머니 에스크로: {}, 포인트 에스크로: {}",
                memberId, result.moneyEscrow(), result.pointEscrow());
        incrementCounter("deduct_composite_escrow");

        return result;
    }

    @Override
    @Transactional
    public long freezeMoney(Long memberId, long amount) {
        log.info("[머니 동결] 시작. 회원 ID: {}, 동결액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.freezeMoney(amount);

        long balanceAfter = wallet.getMoneyBalance();
        log.info("[머니 동결] 완료. 회원 ID: {}, 동결 후 사용 가능 잔액: {}", memberId, balanceAfter);
        incrementCounter("freeze_money");

        return balanceAfter;
    }

    @Override
    @Transactional
    public long unfreezeMoney(Long memberId, long amount) {
        log.info("[머니 동결 해제] 시작. 회원 ID: {}, 해제액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.unfreezeMoney(amount);

        long balanceAfter = wallet.getMoneyBalance();
        log.info("[머니 동결 해제] 완료. 회원 ID: {}, 복원 후 사용 가능 잔액: {}", memberId, balanceAfter);
        incrementCounter("unfreeze_money");

        return balanceAfter;
    }

    @Override
    @Transactional
    public long deductFrozenMoney(Long memberId, long amount) {
        log.info("[동결 머니 차감] 시작. 회원 ID: {}, 차감액: {}", memberId, amount);

        Wallet wallet = findWalletWithLock(memberId);
        wallet.deductFrozenMoney(amount);

        long balanceAfter = wallet.getMoneyBalance();
        log.info("[동결 머니 차감] 완료. 회원 ID: {}, 차감 후 사용 가능 잔액: {}", memberId, balanceAfter);
        incrementCounter("deduct_frozen_money");

        return balanceAfter;
    }

    @Override
    public long getMoneyBalance(Long memberId) {
        log.info("[머니 잔액 조회] 시작. 회원 ID : {}", memberId);
        Wallet wallet = findWallet(memberId);
        return wallet.getMoneyBalance();
    }

    @Override
    public WalletAvailableBalanceResponse getWalletSummary(Long memberId) {
        log.info("[지갑 요약 조회] 시작. 회원 ID : {}", memberId);

        Wallet wallet = findWallet(memberId);

        WalletAvailableBalanceResponse response = WalletAvailableBalanceResponse.from(wallet);
        log.info("[지갑 요약 조회] 완료. 회원 ID : {}", memberId);

        return response;
    }

    private void incrementCounter(String type) {
        metricRecorder.increment("wallet_operation_total", "type", type);
    }

    private Wallet findWallet(Long memberId) {
        Wallet wallet = walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> {
                    log.error("[지갑 조회] 지갑을 찾을 수 없음. 회원 ID : {}", memberId);
                    return new WalletNotFoundException();
                });
        log.debug("[지갑 조회] 조회 성공. 회원 ID : {}", memberId);
        return wallet;
    }

    private Wallet findWalletWithLock(Long memberId) {
        log.debug("[지갑 조회] 비관적 락 사용 시도. 회원 ID : {}", memberId);

        Wallet wallet = walletRepository.findByMemberIdWithLock(memberId)
                .orElseThrow(() -> {
                    log.error("[지갑 조회] 지갑을 찾을 수 없음. 회원 ID : {}", memberId);
                    return new WalletNotFoundException();
                });

        log.debug("[지갑 조회] 비관적 락 획득 성공. 회원 ID : {}", memberId);
        return wallet;
    }
}