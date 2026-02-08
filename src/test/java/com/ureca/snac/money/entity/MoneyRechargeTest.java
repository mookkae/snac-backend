package com.ureca.snac.money.entity;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.exception.InvalidPaymentForRechargeException;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MoneyRecharge 엔티티 단위 테스트")
class MoneyRechargeTest {

    private Member member;

    @BeforeEach
    void setUp() {
        member = MemberFixture.createMember(1L);
    }

    @Nested
    @DisplayName("create 팩토리 메서드")
    class CreateTest {

        @Test
        @DisplayName("성공 : SUCCESS 상태 Payment → MoneyRecharge 생성")
        void create_WithSuccessPayment_CreatesMoneyRecharge() {
            // given
            Payment payment = PaymentFixture.createSuccessPayment(member);

            // when
            MoneyRecharge recharge = MoneyRecharge.create(payment);

            // then
            assertThat(recharge.getMember()).isEqualTo(member);
            assertThat(recharge.getPaidAmountWon()).isEqualTo(payment.getAmount());
            assertThat(recharge.getPayment()).isEqualTo(payment);
        }

        @Test
        @DisplayName("실패 : PENDING 상태 Payment")
        void create_WithPendingPayment_ThrowsException() {
            // given
            Payment payment = PaymentFixture.createPendingPayment(member);

            // when, then
            assertThatThrownBy(() -> MoneyRecharge.create(payment))
                    .isInstanceOf(InvalidPaymentForRechargeException.class);
        }

        @Test
        @DisplayName("실패 : CANCELED 상태 Payment")
        void create_WithCanceledPayment_ThrowsException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCELED)
                    .build();

            // when, then
            assertThatThrownBy(() -> MoneyRecharge.create(payment))
                    .isInstanceOf(InvalidPaymentForRechargeException.class);
        }
    }
}
