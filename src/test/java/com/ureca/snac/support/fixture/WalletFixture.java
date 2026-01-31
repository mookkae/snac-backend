package com.ureca.snac.support.fixture;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.TestReflectionUtils;
import com.ureca.snac.wallet.entity.AssetBalance;
import com.ureca.snac.wallet.entity.Wallet;

//  Wallet 공통 테스트 Fixture
public class WalletFixture {

    public static WalletBuilder builder() {
        return new WalletBuilder();
    }

    public static Wallet createEmptyWallet(Member member) {
        return builder().member(member).build();
    }

    public static Wallet createWalletWithId(Long walletId, Member member) {
        return builder().id(walletId).member(member).build();
    }

    public static class WalletBuilder {
        private Long id = null;
        private Member member;
        private AssetBalance money = AssetBalance.init();
        private AssetBalance point = AssetBalance.init();

        public WalletBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public WalletBuilder member(Member member) {
            this.member = member;
            return this;
        }

        public Wallet build() {
            if (member == null) {
                member = MemberFixture.createMember();
            }

            Wallet wallet = Wallet.builder()
                    .member(member)
                    .money(money)
                    .point(point)
                    .build();

            if (id != null) {
                TestReflectionUtils.setField(wallet, "id", id);
            }

            return wallet;
        }
    }
}
