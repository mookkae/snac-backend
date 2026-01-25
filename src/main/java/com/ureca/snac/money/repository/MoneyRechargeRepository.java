package com.ureca.snac.money.repository;

import com.ureca.snac.money.entity.MoneyRecharge;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MoneyRechargeRepository extends JpaRepository<MoneyRecharge, Long> {

    // 이미 충전 기록이 존재하는지 확인 멱등성 체크
    boolean existsByPaymentId(Long paymentId);
}
