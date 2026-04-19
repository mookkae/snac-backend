package com.ureca.snac.wallet.entity;

import com.ureca.snac.common.BaseTimeEntity;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.wallet.exception.InvalidAmountException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "wallet")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "wallet_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Member member;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "balance", column = @Column(name = "balance_money", nullable = false)),
            @AttributeOverride(name = "escrow", column = @Column(name = "balance_escrow_money", nullable = false)),
            @AttributeOverride(name = "frozen", column = @Column(name = "balance_frozen_money", nullable = false))
    })
    private AssetBalance money;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "balance", column = @Column(name = "balance_point", nullable = false)),
            @AttributeOverride(name = "escrow", column = @Column(name = "balance_escrow_point", nullable = false)),
            // balance_frozen_point 는 현재 미사용 FDS(이상 거래 감지) 또는 회원 정지·탈퇴 시 포인트 동결 용도로 고려할 예정 및 코드 통일성 준수
            @AttributeOverride(name = "frozen", column = @Column(name = "balance_frozen_point", nullable = false))
    })
    private AssetBalance point;

    private Wallet(Member member, AssetBalance money, AssetBalance point) {
        this.member = member;
        this.money = money;
        this.point = point;
    }

    public static Wallet create(Member member) {
        return new Wallet(member, AssetBalance.init(), AssetBalance.init());
    }

    // 머니 입출금
    public void depositMoney(long amount) {
        this.money = this.money.deposit(amount);
    }

    public void withdrawMoney(long amount) {
        this.money = this.money.withdraw(amount);
    }

    // 포인트
    public void depositPoint(long amount) {
        this.point = this.point.deposit(amount);
    }

    // 복합 에스크로
    public void moveCompositeToEscrow(long moneyAmount, long pointAmount) {
        validateCompositeAmounts(moneyAmount, pointAmount);
        AssetBalance newMoney = moneyAmount > 0 ? this.money.moveToEscrow(moneyAmount) : this.money;
        AssetBalance newPoint = pointAmount > 0 ? this.point.moveToEscrow(pointAmount) : this.point;
        this.money = newMoney;
        this.point = newPoint;
    }

    public void cancelCompositeEscrow(long moneyAmount, long pointAmount) {
        validateCompositeAmounts(moneyAmount, pointAmount);
        AssetBalance newMoney = moneyAmount > 0 ? this.money.cancelEscrow(moneyAmount) : this.money;
        AssetBalance newPoint = pointAmount > 0 ? this.point.cancelEscrow(pointAmount) : this.point;
        this.money = newMoney;
        this.point = newPoint;
    }

    public void deductCompositeEscrow(long moneyAmount, long pointAmount) {
        validateCompositeAmounts(moneyAmount, pointAmount);
        AssetBalance newMoney = moneyAmount > 0 ? this.money.deductEscrow(moneyAmount) : this.money;
        AssetBalance newPoint = pointAmount > 0 ? this.point.deductEscrow(pointAmount) : this.point;
        this.money = newMoney;
        this.point = newPoint;
    }

    private void validateCompositeAmounts(long moneyAmount, long pointAmount) {
        if (moneyAmount < 0 || pointAmount < 0 || (moneyAmount == 0 && pointAmount == 0)) {
            throw new InvalidAmountException();
        }
    }

    // 머니 동결 위임 메서드
    public void freezeMoney(long amount) {
        this.money = this.money.freeze(amount);
    }

    public void unfreezeMoney(long amount) {
        this.money = this.money.unfreeze(amount);
    }

    public void deductFrozenMoney(long amount) {
        this.money = this.money.deductFrozen(amount);
    }

    public Long getMoneyBalance() {
        return this.money.getBalance();
    }

    public Long getMoneyEscrow() {
        return this.money.getEscrow();
    }

    public Long getPointBalance() {
        return this.point.getBalance();
    }

    public Long getPointEscrow() {
        return this.point.getEscrow();
    }
}
