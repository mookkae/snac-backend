package com.ureca.snac.wallet.entity;

import com.ureca.snac.support.fixture.WalletFixture;
import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import com.ureca.snac.wallet.exception.InvalidAmountException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wallet 엔티티 단위 테스트
 * composite 연산의 atomicity: 한쪽 자산이 부족하면 다른 쪽도 변경되지 않아야 한다
 */
class WalletEntityTest {

    private static final long MONEY = 10_000L;
    private static final long POINT = 5_000L;

    private com.ureca.snac.wallet.entity.Wallet wallet;

    @BeforeEach
    void setUp() {
        wallet = WalletFixture.createWalletWithBalance(null, MONEY, POINT);
    }

    @Nested
    @DisplayName("moveCompositeToEscrow")
    class MoveCompositeToEscrowTest {

        @Test
        @DisplayName("정상 : 머니+포인트 동시 에스크로 이동")
        void move_both_shouldTransferToEscrow() {
            wallet.moveCompositeToEscrow(6_000L, 3_000L);

            assertThat(wallet.getMoneyBalance()).isEqualTo(4_000L);
            assertThat(wallet.getMoneyEscrow()).isEqualTo(6_000L);
            assertThat(wallet.getPointBalance()).isEqualTo(2_000L);
            assertThat(wallet.getPointEscrow()).isEqualTo(3_000L);
        }

        @Test
        @DisplayName("정상 : 머니만 에스크로 이동 (포인트=0)")
        void move_moneyOnly_shouldTransferMoneyOnly() {
            wallet.moveCompositeToEscrow(6_000L, 0L);

            assertThat(wallet.getMoneyBalance()).isEqualTo(4_000L);
            assertThat(wallet.getMoneyEscrow()).isEqualTo(6_000L);
            assertThat(wallet.getPointBalance()).isEqualTo(POINT);
            assertThat(wallet.getPointEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("정상 : 포인트만 에스크로 이동 (머니=0)")
        void move_pointOnly_shouldTransferPointOnly() {
            wallet.moveCompositeToEscrow(0L, 3_000L);

            assertThat(wallet.getMoneyBalance()).isEqualTo(MONEY);
            assertThat(wallet.getMoneyEscrow()).isEqualTo(0L);
            assertThat(wallet.getPointBalance()).isEqualTo(2_000L);
            assertThat(wallet.getPointEscrow()).isEqualTo(3_000L);
        }

        @Test
        @DisplayName("원자성 : 포인트 부족 시 머니도 변경되지 않아야 한다")
        void move_pointInsufficient_shouldNotMutateMoney() {
            assertThatThrownBy(() -> wallet.moveCompositeToEscrow(6_000L, 99_999L))
                    .isInstanceOf(InsufficientBalanceException.class);

            assertThat(wallet.getMoneyBalance()).isEqualTo(MONEY);
            assertThat(wallet.getMoneyEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("원자성 : 머니 부족 시 포인트도 변경되지 않아야 한다")
        void move_moneyInsufficient_shouldNotMutatePoint() {
            assertThatThrownBy(() -> wallet.moveCompositeToEscrow(99_999L, 3_000L))
                    .isInstanceOf(InsufficientBalanceException.class);

            assertThat(wallet.getPointBalance()).isEqualTo(POINT);
            assertThat(wallet.getPointEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("예외 : 둘 다 0이면 InvalidAmountException")
        void move_bothZero_shouldThrow() {
            assertThatThrownBy(() -> wallet.moveCompositeToEscrow(0L, 0L))
                    .isInstanceOf(InvalidAmountException.class);
        }
    }

    @Nested
    @DisplayName("cancelCompositeEscrow")
    class CancelCompositeEscrowTest {

        @BeforeEach
        void moveToEscrow() {
            wallet.moveCompositeToEscrow(MONEY, POINT); // balance=0/0, escrow=10000/5000
        }

        @Test
        @DisplayName("정상 : 머니+포인트 에스크로 복원")
        void cancel_both_shouldRestoreBalance() {
            wallet.cancelCompositeEscrow(6_000L, 3_000L);

            assertThat(wallet.getMoneyBalance()).isEqualTo(6_000L);
            assertThat(wallet.getMoneyEscrow()).isEqualTo(4_000L);
            assertThat(wallet.getPointBalance()).isEqualTo(3_000L);
            assertThat(wallet.getPointEscrow()).isEqualTo(2_000L);
        }

        @Test
        @DisplayName("원자성 : 포인트 에스크로 부족 시 머니도 변경되지 않아야 한다")
        void cancel_pointInsufficient_shouldNotMutateMoney() {
            assertThatThrownBy(() -> wallet.cancelCompositeEscrow(6_000L, 99_999L))
                    .isInstanceOf(InsufficientBalanceException.class);

            assertThat(wallet.getMoneyBalance()).isEqualTo(0L);
            assertThat(wallet.getMoneyEscrow()).isEqualTo(MONEY);
        }
    }

    @Nested
    @DisplayName("deductCompositeEscrow")
    class DeductCompositeEscrowTest {

        @BeforeEach
        void moveToEscrow() {
            wallet.moveCompositeToEscrow(MONEY, POINT); // balance=0/0, escrow=10000/5000
        }

        @Test
        @DisplayName("정상 : 머니+포인트 에스크로 차감 (소멸)")
        void deduct_both_shouldDecreaseEscrow() {
            wallet.deductCompositeEscrow(6_000L, 3_000L);

            assertThat(wallet.getMoneyBalance()).isEqualTo(0L);
            assertThat(wallet.getMoneyEscrow()).isEqualTo(4_000L);
            assertThat(wallet.getPointBalance()).isEqualTo(0L);
            assertThat(wallet.getPointEscrow()).isEqualTo(2_000L);
        }

        @Test
        @DisplayName("원자성 : 포인트 에스크로 부족 시 머니도 변경되지 않아야 한다")
        void deduct_pointInsufficient_shouldNotMutateMoney() {
            assertThatThrownBy(() -> wallet.deductCompositeEscrow(6_000L, 99_999L))
                    .isInstanceOf(InsufficientBalanceException.class);

            assertThat(wallet.getMoneyEscrow()).isEqualTo(MONEY);
        }
    }
}