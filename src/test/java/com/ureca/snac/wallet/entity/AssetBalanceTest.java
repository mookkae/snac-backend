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
 * 잔액(balance)과 에스크로(escrow), 잔액 동결(frozen)
 */
class AssetBalanceTest {

    private AssetBalance assetBalance;

    @BeforeEach
    void setUp() {
        assetBalance = AssetBalance.init();
    }

    @Test
    @DisplayName("init - balance=0, escrow=0, frozen=0 으로 초기화")
    void init_shouldCreateZeroBalance() {
        assertThat(assetBalance.getBalance()).isEqualTo(0L);
        assertThat(assetBalance.getEscrow()).isEqualTo(0L);
        assertThat(assetBalance.getFrozen()).isEqualTo(0L);
    }

    @Nested
    @DisplayName("deposit")
    class DepositTest {

        @Test
        @DisplayName("정상 : 입금 시 balance 증가")
        void deposit_shouldIncreaseBalance() {
            AssetBalance result = assetBalance.deposit(5000L);

            assertThat(result.getBalance()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("정상 : 연속 입금 시 누적")
        void deposit_multipleTimes_shouldAccumulate() {
            AssetBalance result = assetBalance.deposit(3000L).deposit(2000L);

            assertThat(result.getBalance()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("불변 : 원본 인스턴스 불변")
        void deposit_shouldNotMutateOriginal() {
            assetBalance.deposit(5000L);

            assertThat(assetBalance.getBalance()).isEqualTo(0L);
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
            assetBalance = assetBalance.deposit(10000L);
        }

        @Test
        @DisplayName("정상 : 출금 시 balance 감소")
        void withdraw_shouldDecreaseBalance() {
            AssetBalance result = assetBalance.withdraw(3000L);

            assertThat(result.getBalance()).isEqualTo(7000L);
        }

        @Test
        @DisplayName("정상 : 전액 출금")
        void withdraw_fullAmount_shouldLeaveZero() {
            AssetBalance result = assetBalance.withdraw(10000L);

            assertThat(result.getBalance()).isEqualTo(0L);
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
            assetBalance = assetBalance.deposit(10000L);
        }

        @Test
        @DisplayName("정상 : balance 감소, escrow 증가")
        void moveToEscrow_shouldTransferBalanceToEscrow() {
            AssetBalance result = assetBalance.moveToEscrow(6000L);

            assertThat(result.getBalance()).isEqualTo(4000L);
            assertThat(result.getEscrow()).isEqualTo(6000L);
        }

        @Test
        @DisplayName("정상 : 전액 에스크로 이동")
        void moveToEscrow_fullAmount() {
            AssetBalance result = assetBalance.moveToEscrow(10000L);

            assertThat(result.getBalance()).isEqualTo(0L);
            assertThat(result.getEscrow()).isEqualTo(10000L);
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
    @DisplayName("cancelEscrow")
    class CancelEscrowTest {

        @BeforeEach
        void setUp() {
            assetBalance = assetBalance.deposit(10000L).moveToEscrow(10000L); // balance=0, escrow=10000
        }

        @Test
        @DisplayName("정상 : escrow 감소, balance 증가 (환불)")
        void cancelEscrow_shouldTransferEscrowToBalance() {
            AssetBalance result = assetBalance.cancelEscrow(4000L);

            assertThat(result.getBalance()).isEqualTo(4000L);
            assertThat(result.getEscrow()).isEqualTo(6000L);
        }

        @Test
        @DisplayName("정상 : 전액 에스크로 복원")
        void cancelEscrow_fullAmount() {
            AssetBalance result = assetBalance.cancelEscrow(10000L);

            assertThat(result.getBalance()).isEqualTo(10000L);
            assertThat(result.getEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("예외 : 에스크로 부족 시 InsufficientBalanceException")
        void cancelEscrow_insufficientEscrow_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.cancelEscrow(20000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : 0 이하 금액 시 InvalidAmountException")
        void cancelEscrow_zeroAmount_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.cancelEscrow(0L))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("deductEscrow")
    class DeductEscrowTest {

        @BeforeEach
        void setUp() {
            assetBalance = assetBalance.deposit(10000L).moveToEscrow(10000L); // balance=0, escrow=10000
        }

        @Test
        @DisplayName("정상 : escrow 차감 (소멸), balance 불변")
        void deductEscrow_shouldDecreaseEscrowOnly() {
            AssetBalance result = assetBalance.deductEscrow(7000L);

            assertThat(result.getBalance()).isEqualTo(0L);
            assertThat(result.getEscrow()).isEqualTo(3000L);
        }

        @Test
        @DisplayName("정상 : 전액 에스크로 차감")
        void deductEscrow_fullAmount() {
            AssetBalance result = assetBalance.deductEscrow(10000L);

            assertThat(result.getBalance()).isEqualTo(0L);
            assertThat(result.getEscrow()).isEqualTo(0L);
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
    @DisplayName("freeze")
    class FreezeTest {

        @BeforeEach
        void setUp() {
            assetBalance = assetBalance.deposit(10000L);
        }

        @Test
        @DisplayName("정상 : balance 감소, frozen 증가")
        void freeze_shouldTransferBalanceToFrozen() {
            AssetBalance result = assetBalance.freeze(6000L);

            assertThat(result.getBalance()).isEqualTo(4000L);
            assertThat(result.getFrozen()).isEqualTo(6000L);
        }

        @Test
        @DisplayName("정상 : 전액 동결 시 balance=0, frozen=amount")
        void freeze_fullAmount() {
            AssetBalance result = assetBalance.freeze(10000L);

            assertThat(result.getBalance()).isEqualTo(0L);
            assertThat(result.getFrozen()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("정상 : freeze 후 자산 총량 보존")
        void freeze_totalShouldBeUnchanged() {
            long totalBefore = assetBalance.getBalance() + assetBalance.getEscrow() + assetBalance.getFrozen();
            AssetBalance result = assetBalance.freeze(6000L);

            long totalAfter = result.getBalance() + result.getEscrow() + result.getFrozen();
            assertThat(totalAfter).isEqualTo(totalBefore);
        }

        @Test
        @DisplayName("예외 : balance 부족 시 InsufficientBalanceException")
        void freeze_insufficientBalance_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.freeze(20000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : 0 이하 금액 시 InvalidAmountException")
        void freeze_zeroAmount_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.freeze(0L))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("unfreeze")
    class UnfreezeTest {

        @BeforeEach
        void setUp() {
            assetBalance = assetBalance.deposit(10000L).freeze(10000L); // balance=0, frozen=10000
        }

        @Test
        @DisplayName("정상 : frozen 감소, balance 증가 (동결 해제)")
        void unfreeze_shouldTransferFrozenToBalance() {
            AssetBalance result = assetBalance.unfreeze(4000L);

            assertThat(result.getFrozen()).isEqualTo(6000L);
            assertThat(result.getBalance()).isEqualTo(4000L);
        }

        @Test
        @DisplayName("정상 : 전액 동결 해제")
        void unfreeze_fullAmount() {
            AssetBalance result = assetBalance.unfreeze(10000L);

            assertThat(result.getFrozen()).isEqualTo(0L);
            assertThat(result.getBalance()).isEqualTo(10000L);
        }

        @Test
        @DisplayName("예외 : frozen 부족 시 InsufficientBalanceException")
        void unfreeze_insufficientFrozen_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.unfreeze(20000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : 0 이하 금액 시 InvalidAmountException")
        void unfreeze_zeroAmount_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.unfreeze(0L))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("deductFrozen")
    class DeductFrozenTest {

        @BeforeEach
        void setUp() {
            assetBalance = assetBalance.deposit(10000L).freeze(10000L); // balance=0, frozen=10000
        }

        @Test
        @DisplayName("정상 : frozen 차감 (소멸), balance 불변")
        void deductFrozen_shouldDecreaseFrozenOnly() {
            AssetBalance result = assetBalance.deductFrozen(7000L);

            assertThat(result.getBalance()).isEqualTo(0L);
            assertThat(result.getFrozen()).isEqualTo(3000L);
        }

        @Test
        @DisplayName("정상 : 전액 동결 차감")
        void deductFrozen_fullAmount() {
            AssetBalance result = assetBalance.deductFrozen(10000L);

            assertThat(result.getBalance()).isEqualTo(0L);
            assertThat(result.getFrozen()).isEqualTo(0L);
        }

        @Test
        @DisplayName("예외 : frozen 부족 시 InsufficientBalanceException")
        void deductFrozen_insufficientFrozen_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.deductFrozen(20000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : 0 이하 금액 시 InvalidAmountException")
        void deductFrozen_zeroAmount_shouldThrow() {
            assertThatThrownBy(() -> assetBalance.deductFrozen(0L))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }
}
