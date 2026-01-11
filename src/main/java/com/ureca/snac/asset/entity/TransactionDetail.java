package com.ureca.snac.asset.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 멱등성 체크 및 거래 분류에 사용
 */
@Getter
@RequiredArgsConstructor
public enum TransactionDetail {
    SIGNUP_BONUS("회원가입 축하 포인트");

    private final String displayName;
}
