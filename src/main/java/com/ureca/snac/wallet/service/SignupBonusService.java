package com.ureca.snac.wallet.service;

public interface SignupBonusService {
    /**
     * 회원가입 축하 포인트 지급
     * 멱등성 보장
     *
     * @param memberId 회원 ID
     * @return 새로 지급했으면 true, 이미 지급돼 건너뛰었으면 false
     */
    boolean grantSignupBonus(Long memberId);
}
