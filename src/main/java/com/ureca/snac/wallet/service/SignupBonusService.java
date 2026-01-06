package com.ureca.snac.wallet.service;

public interface SignupBonusService {
    /**
     * 회원가입 축하 포인트 지급
     * 멱등성 보장
     *
     * @param memberId 회원 ID
     */
    void grantSignupBonus(Long memberId);
}
