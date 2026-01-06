package com.ureca.snac.wallet.service;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.TransactionDetail;
import com.ureca.snac.asset.repository.AssetHistoryRepository;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원가입 축하 포인트 지급 서비스 구현체
 * 단일 책임: 보너스 지급 및 내역 기록
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SignupBonusServiceImpl implements SignupBonusService {

    private final MemberRepository memberRepository;
    private final WalletService walletService;
    private final AssetHistoryRepository assetHistoryRepository;

    private static final long SIGNUP_BONUS_AMOUNT = 1000L;

    @Override
    @Transactional
    public void grantSignupBonus(Long memberId) {
        log.info("[회원가입 포인트] 지급 시작. 회원 ID: {}", memberId);

        // 1. 멱등성 체크
        if (isAlreadyGranted(memberId)) {
            log.info("[회원가입 포인트] 이미 지급됨. 중복 방지. 회원 ID: {}", memberId);
            return;
        }

        // 2. Member 조회
        Member member = findMember(memberId);

        // 3. 포인트 지급
        Long balanceAfter = walletService.depositPoint(memberId, SIGNUP_BONUS_AMOUNT);

        log.info("[회원가입 포인트] 지급 완료. 회원 ID: {}, 금액: {}, 잔액: {}",
                memberId, SIGNUP_BONUS_AMOUNT, balanceAfter);

        // 4. AssetHistory 기록
        recordHistory(member, balanceAfter);

        log.info("[회원가입 포인트] 내역 기록 완료. 회원 ID: {}", memberId);
    }

    // 회원가입 포인트 지급 여부 확인
    private boolean isAlreadyGranted(Long memberId) {
        return assetHistoryRepository.existsByMemberIdAndTransactionDetail(
                memberId,
                TransactionDetail.SIGNUP_BONUS
        );
    }

    // 회원 조회
    private Member findMember(Long memberId) {
        return memberRepository.findById(memberId).orElseThrow(
                () -> {
                    log.error("[회원가입 포인트] 회원 조회 실패. 회원 ID: {}", memberId);
                    return new MemberNotFoundException();
                }
        );
    }

    // AssetHistory 기록
    private void recordHistory(Member member, Long balanceAfter) {
        AssetHistory history = AssetHistory.createSignupBonus(
                member,
                SIGNUP_BONUS_AMOUNT,
                balanceAfter
        );
        assetHistoryRepository.save(history);
    }
}