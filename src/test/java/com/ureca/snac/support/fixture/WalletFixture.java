package com.ureca.snac.support.fixture;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.wallet.entity.AssetBalance;
import com.ureca.snac.wallet.entity.Wallet;

import java.lang.reflect.Field;

/**
 * Wallet 공통 테스트 Fixture
 * Feature 2 테스트 시나리오:
 * - 포인트 지급 대상 지갑
 * - 멱등성 검증용 지갑 (이미 포인트 있음)
 */
public class WalletFixture {

    public static WalletBuilder builder() {
        return new WalletBuilder();
    }

    /**
     * 기본 지갑 (잔액 0, 포인트 0)
     */
    public static Wallet createEmptyWallet(Member member) {
        return builder()
                .member(member)
                .build();
    }

    /**
     * 포인트만 지정 지갑 (회원가입 보너스 테스트용)
     */
    public static Wallet createWalletWithPoint(Member member, Long pointAmount) {
        Wallet wallet = builder()
                .member(member)
                .build();

        if (pointAmount > 0) {
            wallet.depositPoint(pointAmount);
        }

        return wallet;
    }

    /**
     * 머니 + 포인트 지정 지갑
     */
    public static Wallet createWallet(Member member, Long moneyAmount, Long pointAmount) {
        Wallet wallet = builder()
                .member(member)
                .build();

        if (moneyAmount > 0) {
            wallet.depositMoney(moneyAmount);
        }
        if (pointAmount > 0) {
            wallet.depositPoint(pointAmount);
        }

        return wallet;
    }

    /**
     * ID 지정 지갑 (Mock용)
     */
    public static Wallet createWalletWithId(Long walletId, Member member) {
        return builder()
                .id(walletId)
                .member(member)
                .build();
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

            // ID 설정 (Reflection)
            if (id != null) {
                setField(wallet, "id", id);
            }

            return wallet;
        }

        // ✅ 캡슐화: Builder 내부로 이동
        private void setField(Object target, String fieldName, Object value) {
            try {
                Field field = getField(target.getClass(), fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (Exception e) {
                throw new RuntimeException("필드 설정 실패: " + fieldName, e);
            }
        }

        private Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                Class<?> superClass = clazz.getSuperclass();
                if (superClass != null) {
                    return getField(superClass, fieldName);
                }
                throw e;
            }
        }
    }
}