package com.ureca.snac.money.service;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeSuccessResponse;

public interface MoneyService {
    /**
     * 머니 충전 요청 서비스
     *
     * @param request 요청 DTO
     * @param member  요청 회원 (name, email을 Toss 위젯 응답에 포함)
     * @return 결제 준비 DTO
     */
    MoneyRechargePreparedResponse prepareRecharge(MoneyRechargeRequest request, Member member);

    /**
     * 토스로부터 성공 콜백 받아서 머니 충전 처리하고 결과 반환
     *
     * @param paymentKey 토스 결제 키
     * @param orderId    우리 시스템 주문 번호
     * @param amount     실제 결제 금액
     * @param memberId   요청 회원 ID
     * @return 충전 성공 정보를 담은 DTO
     */
    MoneyRechargeSuccessResponse processRechargeSuccess(
            String paymentKey, String orderId, Long amount, Long memberId);
}

