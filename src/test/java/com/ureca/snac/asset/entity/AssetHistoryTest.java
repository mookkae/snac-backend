package com.ureca.snac.asset.entity;

import com.ureca.snac.asset.exception.InvalidAssetAmountException;
import com.ureca.snac.asset.exception.InvalidAssetBalanceException;
import com.ureca.snac.asset.exception.InvalidAssetSourceException;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.fixture.MemberFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AssetHistory 엔티티 테스트")
class AssetHistoryTest {

    private Member member;

    @BeforeEach
    void setUp() {
        member = MemberFixture.createMember(1L);
    }

    @Nested
    @DisplayName("createMoneyRecharge 팩토리")
    class CreateMoneyRechargeTest {

        @Test
        @DisplayName("성공 : 머니 충전 내역을 생성")
        void createMoneyRecharge_validInput_createsHistory() {
            // given
            Long paymentId = 100L;
            Long amount = 10000L;
            Long balanceAfter = 10000L;

            // when
            AssetHistory history = AssetHistory.createMoneyRecharge(member, paymentId, amount, balanceAfter);

            // then
            assertThat(history.getMember()).isEqualTo(member);
            assertThat(history.getAssetType()).isEqualTo(AssetType.MONEY);
            assertThat(history.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.RECHARGE);
            assertThat(history.getTransactionDetail()).isNull();
            assertThat(history.getAmount()).isEqualTo(amount);
            assertThat(history.getBalanceAfter()).isEqualTo(balanceAfter);
            assertThat(history.getSourceId()).isEqualTo(paymentId);
            assertThat(history.getTitle()).isEqualTo("충전");
        }

        @Test
        @DisplayName("멱등성 : 멱등키 형식이 RECHARGE:{paymentId}이다")
        void createMoneyRecharge_idempotencyKeyFormat() {
            // given
            Long paymentId = 100L;

            // when
            AssetHistory history = AssetHistory.createMoneyRecharge(member, paymentId, 10000L, 10000L);

            // then
            assertThat(history.getIdempotencyKey()).isEqualTo("RECHARGE:" + paymentId);
        }
    }

    @Nested
    @DisplayName("createMoneyRechargeCancel 팩토리")
    class CreateMoneyRechargeCancelTest {

        @Test
        @DisplayName("성공 : 머니 충전 취소 내역을 생성")
        void createMoneyRechargeCancel_validInput_createsHistory() {
            // given
            Long paymentId = 100L;
            Long amount = 10000L;
            Long balanceAfter = 0L;

            // when
            AssetHistory history = AssetHistory.createMoneyRechargeCancel(member, paymentId, amount, balanceAfter);

            // then
            assertThat(history.getMember()).isEqualTo(member);
            assertThat(history.getAssetType()).isEqualTo(AssetType.MONEY);
            assertThat(history.getTransactionType()).isEqualTo(TransactionType.WITHDRAWAL);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.RECHARGE_CANCEL);
            assertThat(history.getTransactionDetail()).isNull();
            assertThat(history.getAmount()).isEqualTo(amount);
            assertThat(history.getBalanceAfter()).isEqualTo(balanceAfter);
            assertThat(history.getSourceId()).isEqualTo(paymentId);
            assertThat(history.getTitle()).isEqualTo("충전 취소");
        }

        @Test
        @DisplayName("멱등성 : 멱등키 형식이 RECHARGE_CANCEL:{paymentId}이다")
        void createMoneyRechargeCancel_idempotencyKeyFormat() {
            // given
            Long paymentId = 100L;

            // when
            AssetHistory history = AssetHistory.createMoneyRechargeCancel(member, paymentId, 10000L, 0L);

            // then
            assertThat(history.getIdempotencyKey()).isEqualTo("RECHARGE_CANCEL:" + paymentId);
        }
    }

    @Nested
    @DisplayName("createTradeBuy 팩토리")
    class CreateTradeBuyTest {

        @Test
        @DisplayName("성공 : 머니로 구매한 거래 내역을 생성")
        void createTradeBuy_withMoney_createsWithMoneyType() {
            // given
            Long tradeId = 200L;
            String title = "SKT 2GB";
            Long amount = 5000L;
            Long balanceAfter = 5000L;

            // when
            AssetHistory history = AssetHistory.createTradeBuy(member, tradeId, title, AssetType.MONEY, amount, balanceAfter);

            // then
            assertThat(history.getMember()).isEqualTo(member);
            assertThat(history.getAssetType()).isEqualTo(AssetType.MONEY);
            assertThat(history.getTransactionType()).isEqualTo(TransactionType.WITHDRAWAL);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.BUY);
            assertThat(history.getTransactionDetail()).isNull();
            assertThat(history.getAmount()).isEqualTo(amount);
            assertThat(history.getBalanceAfter()).isEqualTo(balanceAfter);
            assertThat(history.getSourceId()).isEqualTo(tradeId);
            assertThat(history.getTitle()).isEqualTo(title);
        }

        @Test
        @DisplayName("성공 : 포인트로 구매한 거래 내역을 생성")
        void createTradeBuy_withPoint_createsWithPointUsageCategory() {
            // given
            Long tradeId = 200L;
            String title = "SKT 2GB";
            Long amount = 5000L;
            Long balanceAfter = 5000L;

            // when
            AssetHistory history = AssetHistory.createTradeBuy(
                    member, tradeId, title, AssetType.POINT, amount, balanceAfter);

            // then
            assertThat(history.getAssetType()).isEqualTo(AssetType.POINT);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.POINT_USAGE);
            assertThat(history.getTransactionDetail()).isNull();
        }

        @Test
        @DisplayName("멱등성 : 멱등키 형식이 {category}:{tradeId}:{assetType}이다")
        void createTradeBuy_idempotencyKeyFormat() {
            // given
            Long tradeId = 200L;

            // when
            AssetHistory moneyHistory = AssetHistory.createTradeBuy(
                    member, tradeId, "상품", AssetType.MONEY, 5000L, 5000L);
            AssetHistory pointHistory = AssetHistory.createTradeBuy(
                    member, tradeId, "상품", AssetType.POINT, 5000L, 5000L);

            // then
            assertThat(moneyHistory.getIdempotencyKey()).isEqualTo("BUY:" + tradeId + ":MONEY");
            assertThat(pointHistory.getIdempotencyKey()).isEqualTo("POINT_USAGE:" + tradeId + ":POINT");
        }
    }

    @Nested
    @DisplayName("createTradeSell 팩토리")
    class CreateTradeSellTest {

        @Test
        @DisplayName("성공 : 판매 완료 내역을 생성")
        void createTradeSell_validInput_createsHistory() {
            // given
            Long tradeId = 200L;
            String title = "SKT 2GB";
            Long amount = 5000L;
            Long balanceAfter = 15000L;

            // when
            AssetHistory history = AssetHistory.createTradeSell(
                    member, tradeId, title, amount, balanceAfter);

            // then
            assertThat(history.getMember()).isEqualTo(member);
            assertThat(history.getAssetType()).isEqualTo(AssetType.MONEY);
            assertThat(history.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.SELL);
            assertThat(history.getTransactionDetail()).isNull();
            assertThat(history.getAmount()).isEqualTo(amount);
            assertThat(history.getBalanceAfter()).isEqualTo(balanceAfter);
            assertThat(history.getSourceId()).isEqualTo(tradeId);
            assertThat(history.getTitle()).isEqualTo(title);
        }

        @Test
        @DisplayName("멱등성 : 멱등키 형식이 SELL:{tradeId}이다")
        void createTradeSell_idempotencyKeyFormat() {
            // given
            Long tradeId = 200L;

            // when
            AssetHistory history = AssetHistory.createTradeSell(
                    member, tradeId, "상품", 5000L, 15000L);

            // then
            assertThat(history.getIdempotencyKey()).isEqualTo("SELL:" + tradeId);
        }
    }

    @Nested
    @DisplayName("createTradeCancelRefund 팩토리")
    class CreateTradeCancelRefundTest {

        @Test
        @DisplayName("성공 : 머니 환불 내역을 생성")
        void createTradeCancelRefund_withMoney_createsWithMoneyType() {
            // given
            Long tradeId = 200L;
            String title = "SKT 2GB";
            Long amount = 5000L;
            Long balanceAfter = 10000L;

            // when
            AssetHistory history = AssetHistory.createTradeCancelRefund(
                    member, tradeId, title, AssetType.MONEY, amount, balanceAfter);

            // then
            assertThat(history.getAssetType()).isEqualTo(AssetType.MONEY);
            assertThat(history.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.TRADE_CANCEL);
            assertThat(history.getTransactionDetail()).isNull();
        }

        @Test
        @DisplayName("성공 : 포인트 환불 내역을 생성")
        void createTradeCancelRefund_withPoint_createsWithPointType() {
            // given
            Long tradeId = 200L;
            String title = "SKT 2GB";
            Long amount = 5000L;
            Long balanceAfter = 10000L;

            // when
            AssetHistory history = AssetHistory.createTradeCancelRefund(
                    member, tradeId, title, AssetType.POINT, amount, balanceAfter);

            // then
            assertThat(history.getAssetType()).isEqualTo(AssetType.POINT);
            assertThat(history.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.TRADE_CANCEL);
            assertThat(history.getTransactionDetail()).isNull();
        }

        @Test
        @DisplayName("멱등성 : 멱등키 형식이 TRADE_CANCEL:{tradeId}:{assetType}이다")
        void createTradeCancelRefund_idempotencyKeyFormat() {
            // given
            Long tradeId = 200L;

            // when
            AssetHistory moneyHistory = AssetHistory.createTradeCancelRefund(
                    member, tradeId, "상품", AssetType.MONEY, 5000L, 10000L);
            AssetHistory pointHistory = AssetHistory.createTradeCancelRefund(
                    member, tradeId, "상품", AssetType.POINT, 5000L, 10000L);

            // then
            assertThat(moneyHistory.getIdempotencyKey()).isEqualTo("TRADE_CANCEL:" + tradeId + ":MONEY");
            assertThat(pointHistory.getIdempotencyKey()).isEqualTo("TRADE_CANCEL:" + tradeId + ":POINT");
        }
    }

    @Nested
    @DisplayName("createSettlement 팩토리")
    class CreateSettlementTest {

        @Test
        @DisplayName("성공 : 정산 출금 내역을 생성")
        void createSettlement_validInput_createsHistory() {
            // given
            Long settlementId = 300L;
            Long amount = 50000L;
            Long balanceAfter = 0L;

            // when
            AssetHistory history = AssetHistory.createSettlement(
                    member, settlementId, amount, balanceAfter);

            // then
            assertThat(history.getMember()).isEqualTo(member);
            assertThat(history.getAssetType()).isEqualTo(AssetType.MONEY);
            assertThat(history.getTransactionType()).isEqualTo(TransactionType.WITHDRAWAL);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.SETTLEMENT);
            assertThat(history.getTransactionDetail()).isNull();
            assertThat(history.getAmount()).isEqualTo(amount);
            assertThat(history.getBalanceAfter()).isEqualTo(balanceAfter);
            assertThat(history.getSourceId()).isEqualTo(settlementId);
        }

        @Test
        @DisplayName("멱등성 : 멱등키 형식이 SETTLEMENT:{settlementId}이다")
        void createSettlement_idempotencyKeyFormat() {
            // given
            Long settlementId = 300L;

            // when
            AssetHistory history = AssetHistory.createSettlement(
                    member, settlementId, 50000L, 0L);

            // then
            assertThat(history.getIdempotencyKey()).isEqualTo("SETTLEMENT:" + settlementId);
        }
    }

    @Nested
    @DisplayName("createSignupBonus 팩토리")
    class CreateSignupBonusTest {

        @Test
        @DisplayName("성공 : 회원가입 보너스 내역을 생성")
        void createSignupBonus_validInput_createsHistory() {
            // given
            Long amount = 1000L;
            Long balanceAfter = 1000L;

            // when
            AssetHistory history = AssetHistory.createSignupBonus(member, balanceAfter);

            // then
            assertThat(history.getMember()).isEqualTo(member);
            assertThat(history.getAssetType()).isEqualTo(AssetType.POINT);
            assertThat(history.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.EVENT);
            assertThat(history.getTransactionDetail()).isEqualTo(TransactionDetail.SIGNUP_BONUS);
            assertThat(history.getAmount()).isEqualTo(amount);
            assertThat(history.getBalanceAfter()).isEqualTo(balanceAfter);
        }

        @Test
        @DisplayName("멱등성 : 멱등키 형식이 SIGNUP_BONUS:{memberId}이다")
        void createSignupBonus_idempotencyKeyFormat() {
            // when
            AssetHistory history = AssetHistory.createSignupBonus(member, 1000L);

            // then
            assertThat(history.getIdempotencyKey()).isEqualTo("SIGNUP_BONUS:" + member.getId());
        }
    }

    @Nested
    @DisplayName("createTradeCompletionBonus 팩토리")
    class CreateTradeCompletionBonusTest {

        @Test
        @DisplayName("성공 : 거래 완료 보너스 내역을 생성")
        void createTradeCompletionBonus_validInput_createsHistory() {
            // given
            Long tradeId = 200L;
            Long amount = 10L;
            Long balanceAfter = 1100L;

            // when
            AssetHistory history = AssetHistory.createTradeCompletionBonus(
                    member, tradeId, balanceAfter);

            // then
            assertThat(history.getMember()).isEqualTo(member);
            assertThat(history.getAssetType()).isEqualTo(AssetType.POINT);
            assertThat(history.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.EVENT);
            assertThat(history.getTransactionDetail()).isEqualTo(TransactionDetail.TRADE_COMPLETION_BONUS);
            assertThat(history.getAmount()).isEqualTo(amount);
            assertThat(history.getBalanceAfter()).isEqualTo(balanceAfter);
            assertThat(history.getSourceId()).isEqualTo(tradeId);
        }

        @Test
        @DisplayName("멱등성 : 멱등키 형식이 TRADE_COMPLETION_BONUS:{tradeId}:{memberId}이다")
        void createTradeCompletionBonus_idempotencyKeyFormat() {
            // given
            Long tradeId = 200L;

            // when
            AssetHistory history = AssetHistory.createTradeCompletionBonus(
                    member, tradeId, 1100L);

            // then
            assertThat(history.getIdempotencyKey()).isEqualTo("TRADE_COMPLETION_BONUS:" + tradeId + ":" + member.getId());
        }
    }

    @Nested
    @DisplayName("createAdminPointGrant 팩토리")
    class CreateAdminPointGrantTest {

        @Test
        @DisplayName("성공 : 관리자 포인트 지급 내역을 생성")
        void createAdminPointGrant_validInput_createsHistory() {
            // given
            Long grantId = 500L;
            Long amount = 5000L;
            Long balanceAfter = 6000L;
            String reason = "테스트 지급";

            // when
            AssetHistory history = AssetHistory.createAdminPointGrant(
                    member, grantId, amount, balanceAfter, reason);

            // then
            assertThat(history.getMember()).isEqualTo(member);
            assertThat(history.getAssetType()).isEqualTo(AssetType.POINT);
            assertThat(history.getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(history.getCategory()).isEqualTo(TransactionCategory.EVENT);
            assertThat(history.getTransactionDetail()).isEqualTo(TransactionDetail.ADMIN_POINT_GRANT);
            assertThat(history.getAmount()).isEqualTo(amount);
            assertThat(history.getBalanceAfter()).isEqualTo(balanceAfter);
            assertThat(history.getSourceId()).isEqualTo(grantId);
            assertThat(history.getTitle()).isEqualTo(reason);
        }

        @Test
        @DisplayName("멱등성 : 멱등키 형식이 ADMIN_POINT_GRANT:{grantId}이다")
        void createAdminPointGrant_idempotencyKeyFormat() {
            // given
            Long grantId = 500L;

            // when
            AssetHistory history = AssetHistory.createAdminPointGrant(
                    member, grantId, 5000L, 6000L, "테스트 지급");

            // then
            assertThat(history.getIdempotencyKey()).isEqualTo("ADMIN_POINT_GRANT:" + grantId);
        }
    }

    @Nested
    @DisplayName("유효성 검증")
    class ValidationTest {

        @Test
        @DisplayName("실패 : amount가 0 이하면 InvalidAssetAmountException 발생")
        void create_withZeroAmount_throwsInvalidAssetAmountException() {
            assertThatThrownBy(() ->
                    AssetHistory.createMoneyRecharge(member, 100L, 0L, 0L))
                    .isInstanceOf(InvalidAssetAmountException.class);
        }

        @Test
        @DisplayName("실패 : amount가 null이면 InvalidAssetAmountException 발생")
        void create_withNullAmount_throwsInvalidAssetAmountException() {
            assertThatThrownBy(() ->
                    AssetHistory.createMoneyRecharge(member, 100L, null, 0L))
                    .isInstanceOf(InvalidAssetAmountException.class);
        }

        @Test
        @DisplayName("실패 : balanceAfter가 음수면 InvalidAssetBalanceException 발생")
        void create_withNegativeBalance_throwsInvalidAssetBalanceException() {
            assertThatThrownBy(() ->
                    AssetHistory.createMoneyRecharge(member, 100L, 1000L, -1L))
                    .isInstanceOf(InvalidAssetBalanceException.class);
        }

        @Test
        @DisplayName("실패 : member가 null이면 InvalidAssetSourceException 발생")
        void create_withNullMember_throwsInvalidAssetSourceException() {
            assertThatThrownBy(() ->
                    AssetHistory.createMoneyRecharge(null, 100L, 1000L, 1000L))
                    .isInstanceOf(InvalidAssetSourceException.class);
        }

        @Test
        @DisplayName("실패 : sourceId가 null이면 InvalidAssetSourceException 발생")
        void create_withNullSourceId_throwsInvalidAssetSourceException() {
            assertThatThrownBy(() ->
                    AssetHistory.createMoneyRecharge(member, null, 1000L, 1000L))
                    .isInstanceOf(InvalidAssetSourceException.class);
        }

        @Test
        @DisplayName("실패 : sourceId가 0 이하면 InvalidAssetSourceException 발생")
        void create_withZeroSourceId_throwsInvalidAssetSourceException() {
            assertThatThrownBy(() ->
                    AssetHistory.createMoneyRecharge(member, 0L, 1000L, 1000L))
                    .isInstanceOf(InvalidAssetSourceException.class);
        }
    }
}
