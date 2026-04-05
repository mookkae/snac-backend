package com.ureca.snac.wallet.entity;

import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import com.ureca.snac.wallet.exception.InvalidAmountException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AssetBalance Value Object 단위 테스트
 * 잔액(balance)과 에스크로(escrow) 상태 관리의 정확성을 검증한다.
 */
class AssetBalanceTest {

    private AssetBalance assetBalance;

    @BeforeEach
    void setUp() {
        assetBalance = AssetBalance.init();
    }

    @Test
    @DisplayName("init - balance=0, escrow=0 으로 초기화")
    void init_shouldCreateZeroBalance() {
        assertThat(assetBalance.getBalance()).isEqualTo(0L);
        assertThat(assetBalance.getEscrow()).isEqualTo(0L);
    }

    @Nested
    @DisplayName("deposit")
    class DepositTest {

        @Test
        @DisplayName("정상 : 입금 시 balance 증가")
        void deposit_shouldIncreaseBalance() {
            assetBalance.deposit(5000L);

            assertThat(assetBalance.getBalance()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("정상 : 연속 입금 시 누적")
        void deposit_multipleTimes_shouldAccumulate() {
            assetBalance.deposit(3000L);
            assetBalance.deposit(2000L);

            assertThat(assetBalance.getBalance()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("예외 : 0 이하 금액 시 InvalidAmountException")
        void deposit_zeroAmount_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.deposit(0L))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("예외 : 음수 금액 시 InvalidAmountException")
        void deposit_negativeAmount_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.deposit(-1000L))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("withdraw")
    class WithdrawTest {

        @BeforeEach
        void setUp() {
            assetBalance.deposit(10000L);
        }

        @Test
        @DisplayName("정상 : 출금 시 balance 감소")
        void withdraw_shouldDecreaseBalance() {
            assetBalance.withdraw(3000L);

            assertThat(assetBalance.getBalance()).isEqualTo(7000L);
        }

        @Test
        @DisplayName("정상 : 전액 출금")
        void withdraw_fullAmount_shouldLeaveZero() {
            assetBalance.withdraw(10000L);

            assertThat(assetBalance.getBalance()).isEqualTo(0L);
        }

        @Test
        @DisplayName("예외 : 잔액 부족 시 InsufficientBalanceException")
        void withdraw_insufficientBalance_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.withdraw(20000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : 0 이하 금액 시 InvalidAmountException")
        void withdraw_zeroAmount_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.withdraw(0L))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("moveToEscrow")
    class MoveToEscrowTest {

        @BeforeEach
        void setUp() {
            assetBalance.deposit(10000L);
        }

        @Test
        @DisplayName("정상 : balance 감소, escrow 증가")
        void moveToEscrow_shouldTransferBalanceToEscrow() {
            assetBalance.moveToEscrow(6000L);

            assertThat(assetBalance.getBalance()).isEqualTo(4000L);
            assertThat(assetBalance.getEscrow()).isEqualTo(6000L);
        }

        @Test
        @DisplayName("정상 : 전액 에스크로 이동")
        void moveToEscrow_fullAmount() {
            assetBalance.moveToEscrow(10000L);

            assertThat(assetBalance.getBalance()).isEqualTo(0L);
            assertThat(assetBalance.getEscrow()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("예외 : 잔액 부족 시 InsufficientBalanceException")
        void moveToEscrow_insufficientBalance_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.moveToEscrow(20000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : 0 이하 금액 시 InvalidAmountException")
        void moveToEscrow_zeroAmount_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.moveToEscrow(0L))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("releaseEscrow")
    class ReleaseEscrowTest {

        @BeforeEach
        void setUp() {
            assetBalance.deposit(10000L);
            assetBalance.moveToEscrow(10000L); // balance=0, escrow=10000
        }

        @Test
        @DisplayName("정상 : escrow 감소, balance 증가 (환불)")
        void releaseEscrow_shouldTransferEscrowToBalance() {
            assetBalance.releaseEscrow(4000L);

            assertThat(assetBalance.getBalance()).isEqualTo(4000L);
            assertThat(assetBalance.getEscrow()).isEqualTo(6000L);
        }

        @Test
        @DisplayName("정상 : 전액 에스크로 복원")
        void releaseEscrow_fullAmount() {
            assetBalance.releaseEscrow(10000L);

            assertThat(assetBalance.getBalance()).isEqualTo(10000L);
            assertThat(assetBalance.getEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("예외 : 에스크로 부족 시 InsufficientBalanceException")
        void releaseEscrow_insufficientEscrow_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.releaseEscrow(20000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : 0 이하 금액 시 InvalidAmountException")
        void releaseEscrow_zeroAmount_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.releaseEscrow(0L))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("deductEscrow")
    class DeductEscrowTest {

        @BeforeEach
        void setUp() {
            assetBalance.deposit(10000L);
            assetBalance.moveToEscrow(10000L); // balance=0, escrow=10000
        }

        @Test
        @DisplayName("정상 : escrow 차감 (소멸), balance 불변")
        void deductEscrow_shouldDecreaseEscrowOnly() {
            assetBalance.deductEscrow(7000L);

            assertThat(assetBalance.getBalance()).isEqualTo(0L);
            assertThat(assetBalance.getEscrow()).isEqualTo(3000L);
        }

        @Test
        @DisplayName("정상 : 전액 에스크로 차감")
        void deductEscrow_fullAmount() {
            assetBalance.deductEscrow(10000L);

            assertThat(assetBalance.getBalance()).isEqualTo(0L);
            assertThat(assetBalance.getEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("예외 : 에스크로 부족 시 InsufficientBalanceException")
        void deductEscrow_insufficientEscrow_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.deductEscrow(20000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : 0 이하 금액 시 InvalidAmountException")
        void deductEscrow_zeroAmount_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.deductEscrow(0L))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("getTotal")
    class GetTotalTest {

        @Test
        @DisplayName("정상 : balance + escrow 합산")
        void getTotal_shouldReturnSumOfBalanceAndEscrow() {
            assetBalance.deposit(10000L);
            assetBalance.moveToEscrow(3000L);

            assertThat(assetBalance.getTotal()).isEqualTo(10000L);
            assertThat(assetBalance.getBalance()).isEqualTo(7000L);
            assertThat(assetBalance.getEscrow()).isEqualTo(3000L);
        }

        @Test
        @DisplayName("정상 : 초기 상태에서 total=0")
        void getTotal_initial_shouldBeZero() {
            assertThat(assetBalance.getTotal()).isEqualTo(0L);
        }
    }
}
