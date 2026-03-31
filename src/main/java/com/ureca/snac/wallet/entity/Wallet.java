package com.ureca.snac.wallet.entity;

import com.ureca.snac.common.BaseTimeEntity;
import com.ureca.snac.member.entity.Member;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
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
            @AttributeOverride(name = "escrow", column = @Column(name = "balance_escrow_money", nullable = false))
    })
    private AssetBalance money;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "balance", column = @Column(name = "balance_point", nullable = false)),
            @AttributeOverride(name = "escrow", column = @Column(name = "balance_escrow_point", nullable = false))
    })
    private AssetBalance point;

    @Builder
    private Wallet(Member member, AssetBalance money, AssetBalance point) {
        this.member = member;
        this.money = money;
        this.point = point;
    }

    public static Wallet create(Member member) {
        return Wallet.builder()
                .member(member)
                .money(AssetBalance.init())
                .point(AssetBalance.init())
                .build();
    }

    // 머니
    public void depositMoney(long amount) {
        this.money.deposit(amount);
    }

    public void withdrawMoney(long amount) {
        this.money.withdraw(amount);
    }

    // 에스크로 위임 메소드
    public void moveMoneyToEscrow(long amount) {
        this.money.moveToEscrow(amount);
    }

    public void releaseMoneyEscrow(long amount) {
        this.money.releaseEscrow(amount);
    }

    public void deductMoneyEscrow(long amount) {
        this.money.deductEscrow(amount);
    }

    // 포인트
    public void depositPoint(long amount) {
        this.point.deposit(amount);
    }

    public void withdrawPoint(long amount) {
        this.point.withdraw(amount);
    }

    public void movePointToEscrow(long amount) {
        this.point.moveToEscrow(amount);
    }

    public void releasePointEscrow(long amount) {
        this.point.releaseEscrow(amount);
    }

    public void deductPointEscrow(long amount) {
        this.point.deductEscrow(amount);
    }

    public Long getMoneyBalance() {
        return this.money.getBalance();
    }

    public Long getMoneyEscrow() {
        return this.money.getEscrow();
    }

    public Long getTotalMoney() {
        return this.money.getTotal();
    }

    public Long getPointBalance() {
        return this.point.getBalance();
    }

    public Long getPointEscrow() {
        return this.point.getEscrow();
    }

    public Long getTotalPoint() {
        return this.point.getTotal();
    }
}
