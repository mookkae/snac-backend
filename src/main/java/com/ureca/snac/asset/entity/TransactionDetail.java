package com.ureca.snac.asset.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 이벤트성 거래의 멱등성 체크용 enum
 * 이벤트 지급(포인트 보너스 등)에만 사용
 * 일반 거래(충전, 구매, 판매 등)는 null
 */
@Getter
@RequiredArgsConstructor
public enum TransactionDetail {
    SIGNUP_BONUS("회원가입 축하 포인트", 1000L),
    TRADE_COMPLETION_BONUS("거래 완료 보너스", 10L),
    ADMIN_POINT_GRANT("관리자 포인트 지급", 0L);

    private final String displayName;
    private final long defaultAmount;
}
