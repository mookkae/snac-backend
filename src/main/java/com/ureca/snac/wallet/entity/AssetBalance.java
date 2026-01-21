package com.ureca.snac.wallet.entity;

import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import com.ureca.snac.wallet.exception.InvalidAmountException;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

/**
 * 자산 잔액 및 에스크로 상태 관리
 * <p>
 * 머니/포인트의 입출금 에스크로 캡슐화
 * SRP : 잔액 관리와 에스크로 처리만 담당
 * <p>
 * 사용 가능 잔액과 에스크로 잔액 분리 관리
 * 에스크로 : 거래 중인 금액으로 사용 불가능하지만 소유권은 유지
 */

@Embeddable
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssetBalance {

    @Column(nullable = false)
    private Long balance;

    @Column(nullable = false)
    private Long escrow;

    // 팩토리 메소드 강제
    private AssetBalance(Long balance, Long escrow) {
        this.balance = balance;
        this.escrow = escrow;
    }

    public static AssetBalance init() {
        return new AssetBalance(0L, 0L);
    }

    public void deposit(long amount) {
        validatePositiveAmount(amount);
        this.balance += amount;
    }

    public void withdraw(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.balance, amount);
        this.balance -= amount;
    }

    /**
     * 에스크로 보관
     * <p>
     * 구매자 구매 시 구매자의 잔액 차감 하고 에스크로 증가
     *
     * @param amount 에스크로로 이동할 금액
     */
    public void moveToEscrow(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.balance, amount);

        this.balance -= amount;
        this.escrow += amount;
    }

    /**
     * 에스크로 잔액을 사용 가능 잔액으로 복원 (환불 시)
     * 거래 취소 또는 환불 에스크로 차감하고 잔액 증가
     *
     * @param amount 복원할 금액
     */
    public void releaseEscrow(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.escrow, amount);

        this.balance += amount;
        this.escrow -= amount;
    }

    /**
     * 에스크로 잔액 차감 (정산 완료 시)
     * <p>
     * 구매 확정 시 구매자 escrow 차감하고
     * 판매자의 잔액을 증가함
     *
     * @param amount 차감할 금액
     */
    public void deductEscrow(long amount) {
        validatePositiveAmount(amount);
        validateSufficientBalance(this.escrow, amount);

        this.escrow -= amount;
    }

    public Long getTotal() {
        return this.balance + this.escrow;
    }

    private void validatePositiveAmount(long amount) {
        if (amount <= 0) {
            throw new InvalidAmountException();
        }
    }

    private void validateSufficientBalance(Long currentBalance, long amount) {
        if (currentBalance < amount) {
            throw new InsufficientBalanceException();
        }
    }
}