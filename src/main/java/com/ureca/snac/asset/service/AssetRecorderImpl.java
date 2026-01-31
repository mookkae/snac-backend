package com.ureca.snac.asset.service;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.AssetType;
import com.ureca.snac.asset.repository.AssetHistoryRepository;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자산 내역 기록 서비스 구현체
 * 호출자의 트랜잭션에 참여하여 동기적으로 자산 내역을 기록
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class AssetRecorderImpl implements AssetRecorder {

    private final AssetHistoryRepository assetHistoryRepository;
    private final MemberRepository memberRepository;

    @Override
    public void recordMoneyRecharge(Long memberId, Long paymentId, Long amount, Long balanceAfter) {
        log.info("[자산 내역 기록] 머니 충전 기록. memberId: {}, paymentId: {}, amount: {}",
                memberId, paymentId, amount);

        Member member = findMemberById(memberId);
        AssetHistory history = AssetHistory.createMoneyRecharge(member, paymentId, amount, balanceAfter);
        saveWithIdempotency(history);
    }

    @Override
    public void recordMoneyRechargeCancel(Long memberId, Long paymentId, Long amount, Long balanceAfter) {
        log.info("[자산 내역 기록] 머니 충전 취소 기록. memberId: {}, paymentId: {}, amount: {}",
                memberId, paymentId, amount);

        Member member = findMemberById(memberId);
        AssetHistory history = AssetHistory.createMoneyRechargeCancel(member, paymentId, amount, balanceAfter);
        saveWithIdempotency(history);
    }

    @Override
    public void recordTradeBuy(Long memberId, Long tradeId, String title,
                               AssetType assetType, Long amount, Long balanceAfter) {
        log.info("[자산 내역 기록] 거래 구매 기록. memberId: {}, tradeId: {}, assetType: {}, amount: {}",
                memberId, tradeId, assetType, amount);

        Member member = findMemberById(memberId);
        AssetHistory history = AssetHistory.createTradeBuy(
                member, tradeId, title, assetType, amount, balanceAfter);
        saveWithIdempotency(history);
    }

    @Override
    public void recordTradeSell(Long memberId, Long tradeId, String title, Long amount, Long balanceAfter) {
        log.info("[자산 내역 기록] 거래 판매 기록. memberId: {}, tradeId: {}, amount: {}",
                memberId, tradeId, amount);

        Member member = findMemberById(memberId);
        AssetHistory history = AssetHistory.createTradeSell(member, tradeId, title, amount, balanceAfter);
        saveWithIdempotency(history);
    }

    @Override
    public void recordTradeCancelRefund(Long memberId, Long tradeId, String title,
                                        AssetType assetType, Long amount, Long balanceAfter) {
        log.info("[자산 내역 기록] 거래 취소 환불 기록. memberId: {}, tradeId: {}, assetType: {}, amount: {}",
                memberId, tradeId, assetType, amount);

        Member member = findMemberById(memberId);
        AssetHistory history = AssetHistory.createTradeCancelRefund(
                member, tradeId, title, assetType, amount, balanceAfter);
        saveWithIdempotency(history);
    }

    @Override
    public void recordSignupBonus(Long memberId, Long amount, Long balanceAfter) {
        log.info("[자산 내역 기록] 가입 보너스 기록. memberId: {}, amount: {}", memberId, amount);

        Member member = findMemberById(memberId);
        AssetHistory history = AssetHistory.createSignupBonus(member, balanceAfter);
        saveWithIdempotency(history);
    }

    @Override
    public void recordTradeCompletionBonus(Long memberId, Long tradeId, Long amount, Long balanceAfter) {
        log.info("[자산 내역 기록] 거래 완료 보너스 기록. memberId: {}, tradeId: {}, amount: {}",
                memberId, tradeId, amount);

        Member member = findMemberById(memberId);
        AssetHistory history = AssetHistory.createTradeCompletionBonus(member, tradeId, balanceAfter);
        saveWithIdempotency(history);
    }

    @Override
    public void recordSettlement(Long memberId, Long settlementId, Long amount, Long balanceAfter) {
        log.info("[자산 내역 기록] 정산 기록. memberId: {}, settlementId: {}, amount: {}",
                memberId, settlementId, amount);

        Member member = findMemberById(memberId);
        AssetHistory history = AssetHistory.createSettlement(member, settlementId, amount, balanceAfter);
        saveWithIdempotency(history);
    }

    private Member findMemberById(Long memberId) {
        return memberRepository.findById(memberId)
                .orElseThrow(MemberNotFoundException::new);
    }

    // 멱등성 보장을 위한 저장 메서드
    private void saveWithIdempotency(AssetHistory history) {
        String idempotencyKey = history.getIdempotencyKey();
        if (assetHistoryRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.warn("[자산 내역 기록] 중복 요청 무시 (멱등성). idempotencyKey: {}", idempotencyKey);
            return;
        }

        assetHistoryRepository.save(history);
        log.info("[자산 내역 기록] 저장 완료. idempotencyKey: {}", idempotencyKey);
    }
}
