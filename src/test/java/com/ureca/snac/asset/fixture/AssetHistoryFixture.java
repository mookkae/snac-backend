package com.ureca.snac.asset.fixture;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.AssetType;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.TestReflectionUtils;

import java.time.LocalDateTime;

/**
 * AssetHistory 테스트 Fixture
 * AssetHistoryServiceTest, AssetHistoryRepositoryTest
 */
public class AssetHistoryFixture {

    public static AssetHistory createMoneyRecharge(Member member, Long paymentId, Long amount, Long balanceAfter) {
        return AssetHistory.createMoneyRecharge(member, paymentId, amount, balanceAfter);
    }

    public static AssetHistory createTradeBuyMoney(Member member) {
        return AssetHistory.createTradeBuy(member, 200L, "테스트 상품", AssetType.MONEY, 10000L, 10000L);
    }

    public static AssetHistory withId(AssetHistory history, Long id) {
        TestReflectionUtils.setField(history, "id", id);
        return history;
    }

    public static AssetHistory withCreatedAt(AssetHistory history, LocalDateTime createdAt) {
        TestReflectionUtils.setField(history, "createdAt", createdAt);
        return history;
    }
}
