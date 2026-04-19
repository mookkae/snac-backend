package com.ureca.snac.wallet.entity;

import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import com.ureca.snac.wallet.exception.InvalidAmountException;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 머니/포인트의 잔액, 에스크로 보관액, 충전 취소시 동결 금액
 * 에스크로: 거래 중인 금액으로 사용 불가능하지만 소유
 * frozen : 충전 취소로 소비 불가하지만 취소 예약된 금액
 * 머니 : 결제 취소 환불 절차 중 동결 (Toss 결과 확정 전까지 소비 불가)
 * 포인트 : 현재 미사용. FDS(이상 거래 감지) 또는 회원 정지·탈퇴 시 자산 동결 용도 고려
 */

@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetBalance {

    private Long balance;

    private Long escrow;

    private Long frozen;

    private AssetBalance(Long balance, Long escrow, Long frozen) {
        this.balance = balance;
        this.escrow = escrow;
        this.frozen = frozen;
    }

    public static AssetBalance init() {
        return new AssetBalance(0L, 0L, 0L);
    }

    public AssetBalance deposit(long amount) {
        validatePositiveAmount(amount);
        return new AssetBalance(this.balance + amount, this.escrow, this.frozen);
    }

    public AssetBalance withdraw(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.balance, amount);
        return new AssetBalance(this.balance - amount, this.escrow, this.frozen);
    }

    public AssetBalance moveToEscrow(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.balance, amount);
        return new AssetBalance(this.balance - amount, this.escrow + amount, this.frozen);
    }

    public AssetBalance cancelEscrow(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.escrow, amount);
        return new AssetBalance(this.balance + amount, this.escrow - amount, this.frozen);
    }

    public AssetBalance deductEscrow(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.escrow, amount);
        return new AssetBalance(this.balance, this.escrow - amount, this.frozen);
    }

    public AssetBalance freeze(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.balance, amount);
        return new AssetBalance(this.balance - amount, this.escrow, this.frozen + amount);
    }

    public AssetBalance unfreeze(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.frozen, amount);
        return new AssetBalance(this.balance + amount, this.escrow, this.frozen - amount);
    }

    public AssetBalance deductFrozen(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.frozen, amount);
        return new AssetBalance(this.balance, this.escrow, this.frozen - amount);
    }

    private void validatePositiveAmount(long amount) {
        if (amount <= 0) {
            throw new InvalidAmountException();
        }
    }

    private void validateSufficientBalance(long currentBalance, long amount) {
        if (currentBalance < amount) {
            throw new InsufficientBalanceException();
        }
    }
}
