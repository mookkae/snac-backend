package com.ureca.snac.support.fixture;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.TestReflectionUtils;
import com.ureca.snac.wallet.entity.Wallet;

// Wallet 공통 테스트 Fixture (Dead Code 정리 및 빌더 중심)
public class WalletFixture {

    public static WalletBuilder builder() {
        return new WalletBuilder();
    }

    /**
     * 특정 잔액을 가진 지갑 생성 편의 메서드
     */
    public static Wallet createWalletWithBalance(Member member, long money, long point) {
        return builder()
                .member(member)
                .money(money)
                .point(point)
                .build();
    }

    public static class WalletBuilder {
        private Long id = null;
        private Member member;
        private long money = 0L;
        private long point = 0L;

        public WalletBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public WalletBuilder member(Member member) {
            this.member = member;
            return this;
        }

        public WalletBuilder money(long money) {
            this.money = money;
            return this;
        }

        public WalletBuilder point(long point) {
            this.point = point;
            return this;
        }

        public Wallet build() {
            if (member == null) {
                member = MemberFixture.createMember();
            }

            Wallet wallet = Wallet.create(member);

            if (money > 0) {
                wallet.depositMoney(money);
            }
            if (point > 0) {
                wallet.depositPoint(point);
            }

            if (id != null) {
                TestReflectionUtils.setField(wallet, "id", id);
            }

            return wallet;
        }
    }
}
