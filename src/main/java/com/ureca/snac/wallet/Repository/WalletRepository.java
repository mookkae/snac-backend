package com.ureca.snac.wallet.repository;

import com.ureca.snac.wallet.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    Optional<Wallet> findByMemberId(Long memberId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w where w.member.id = :memberId")
    Optional<Wallet> findByMemberIdWithLock(@Param("memberId") Long memberId);

    boolean existsByMemberId(Long memberId);

    // CAS: Atomic SQL UPDATE — 잔액 검증을 WHERE 절에서 수행, rows affected = 0 이면 부족
    // 머니 입출금
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_money = balance_money + :amount WHERE member_id = :memberId",
            nativeQuery = true)
    int casDepositMoney(@Param("memberId") Long memberId, @Param("amount") long amount);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_money = balance_money - :amount WHERE member_id = :memberId AND balance_money >= :amount",
            nativeQuery = true)
    int casWithdrawMoney(@Param("memberId") Long memberId, @Param("amount") long amount);

    // 포인트 입금
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_point = balance_point + :amount WHERE member_id = :memberId",
            nativeQuery = true)
    int casDepositPoint(@Param("memberId") Long memberId, @Param("amount") long amount);

    // 에스크로 이동 (balance -> escrow)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_money = balance_money - :amount, balance_escrow_money = balance_escrow_money + :amount " +
            "WHERE member_id = :memberId AND balance_money >= :amount",
            nativeQuery = true)
    int casMoveMoneyToEscrow(@Param("memberId") Long memberId, @Param("amount") long amount);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_point = balance_point - :amount, balance_escrow_point = balance_escrow_point + :amount " +
            "WHERE member_id = :memberId AND balance_point >= :amount",
            nativeQuery = true)
    int casMovePointToEscrow(@Param("memberId") Long memberId, @Param("amount") long amount);

    // 에스크로 환불 (escrow -> balance)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_money = balance_money + :amount, balance_escrow_money = balance_escrow_money - :amount " +
            "WHERE member_id = :memberId AND balance_escrow_money >= :amount",
            nativeQuery = true)
    int casCancelMoneyEscrow(@Param("memberId") Long memberId, @Param("amount") long amount);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_point = balance_point + :amount, balance_escrow_point = balance_escrow_point - :amount " +
            "WHERE member_id = :memberId AND balance_escrow_point >= :amount",
            nativeQuery = true)
    int casCancelPointEscrow(@Param("memberId") Long memberId, @Param("amount") long amount);

    // 에스크로 차감 (escrow -> 소멸)
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_escrow_money = balance_escrow_money - :amount WHERE member_id = :memberId AND balance_escrow_money >= :amount",
            nativeQuery = true)
    int casDeductMoneyEscrow(@Param("memberId") Long memberId, @Param("amount") long amount);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_escrow_point = balance_escrow_point - :amount WHERE member_id = :memberId AND balance_escrow_point >= :amount",
            nativeQuery = true)
    int casDeductPointEscrow(@Param("memberId") Long memberId, @Param("amount") long amount);

    // 머니 동결
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_money = balance_money - :amount, balance_frozen_money = balance_frozen_money + :amount " +
            "WHERE member_id = :memberId AND balance_money >= :amount",
            nativeQuery = true)
    int casFreezeMoney(@Param("memberId") Long memberId, @Param("amount") long amount);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_money = balance_money + :amount, balance_frozen_money = balance_frozen_money - :amount " +
            "WHERE member_id = :memberId AND balance_frozen_money >= :amount",
            nativeQuery = true)
    int casUnfreezeMoney(@Param("memberId") Long memberId, @Param("amount") long amount);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE wallet SET balance_frozen_money = balance_frozen_money - :amount WHERE member_id = :memberId AND balance_frozen_money >= :amount",
            nativeQuery = true)
    int casDeductFrozenMoney(@Param("memberId") Long memberId, @Param("amount") long amount);
}
