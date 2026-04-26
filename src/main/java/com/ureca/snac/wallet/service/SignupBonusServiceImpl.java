package com.ureca.snac.wallet.service;

import com.ureca.snac.asset.entity.TransactionDetail;
import com.ureca.snac.asset.service.AssetRecorder;
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
public class SignupBonusServiceImpl implements SignupBonusService {

    private final WalletService walletService;
    private final AssetRecorder assetRecorder;

    @Override
    @Transactional
    public void grantSignupBonus(Long memberId) {
        log.info("[회원가입 포인트] 지급 시작. 회원 ID: {}", memberId);

        // 1. 멱등성 사전 조회: 순차 중복을 조기 차단해 불필요한 락 경쟁을 방지
        // mq의 스레드가 concurrency 1 환경이라 listener 중복은 항상 순차적이므로 대부분 여기서 잡는다.
        // 최종 멱등성은 AssetHistory 의 멱등키가 unique 제약으로 보장
        if (isAlreadyGranted(memberId)) {
            log.info("[회원가입 포인트] 이미 지급됨. 중복 방지. 회원 ID: {}", memberId);
            return;
        }

        // 2. 포인트 지급
        long amount = TransactionDetail.SIGNUP_BONUS.getDefaultAmount();
        long balanceAfter = walletService.depositPoint(memberId, amount);

        log.info("[회원가입 포인트] 지급 완료. 회원 ID: {}, 금액: {}, 잔액: {}",
                memberId, amount, balanceAfter);

        // 3. AssetHistory 기록 (AssetRecorder에서 멱등성 처리)
        assetRecorder.recordSignupBonus(memberId, amount, balanceAfter);

        log.info("[회원가입 포인트] 내역 기록 완료. 회원 ID: {}", memberId);
    }

    // 회원가입 포인트 지급 여부 확인
    private boolean isAlreadyGranted(Long memberId) {
        return assetRecorder.hasSignupBonusRecord(memberId);
    }
}