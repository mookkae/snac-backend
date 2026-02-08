package com.ureca.snac.payment.entity;

import com.ureca.snac.common.exception.BusinessException;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.exception.*;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Payment 엔티티 단위 테스트")
class PaymentTest {

    private Member member;

    @BeforeEach
    void setUp() {
        member = MemberFixture.createMember(1L);
    }

    @Nested
    @DisplayName("prepare 팩토리 메서드")
    class PrepareTest {

        @Test
        @DisplayName("성공 : 정상 금액으로 Payment 생성")
        void prepare_WithValidAmount_CreatesPayment() {
            // when
            Payment payment = Payment.prepare(member, 10000L);

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getAmount()).isEqualTo(10000L);
            assertThat(payment.getMember()).isEqualTo(member);
            assertThat(payment.getOrderId()).startsWith("snac_order_");
        }

        @Test
        @DisplayName("실패 : amount가 null")
        void prepare_NullAmount_ThrowsException() {
            assertThatThrownBy(() -> Payment.prepare(member, null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("실패 : amount가 0")
        void prepare_ZeroAmount_ThrowsException() {
            assertThatThrownBy(() -> Payment.prepare(member, 0L))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("실패 : amount가 음수")
        void prepare_NegativeAmount_ThrowsException() {
            assertThatThrownBy(() -> Payment.prepare(member, -1000L))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("complete 메서드")
    class CompleteTest {

        @Test
        @DisplayName("성공 : PENDING -> SUCCESS 전환")
        void complete_FromPending_TransitionsToSuccess() {
            // given
            Payment payment = Payment.prepare(member, 10000L);
            OffsetDateTime paidAt = OffsetDateTime.now();

            // when
            payment.complete("pk_test", PaymentMethod.CARD, paidAt);

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(payment.getPaymentKey()).isEqualTo("pk_test");
            assertThat(payment.getMethod()).isEqualTo(PaymentMethod.CARD);
            assertThat(payment.getPaidAt()).isEqualTo(paidAt);
        }

        @Test
        @DisplayName("실패 : SUCCESS 상태에서 complete 호출")
        void complete_FromSuccess_ThrowsException() {
            // given
            Payment payment = PaymentFixture.createSuccessPayment(member);

            // when, then
            assertThatThrownBy(() ->
                    payment.complete("pk_test", PaymentMethod.CARD, OffsetDateTime.now())
            ).isInstanceOf(PaymentAlreadyProcessedPaymentException.class);
        }

        @Test
        @DisplayName("실패 : CANCELED 상태에서 complete 호출")
        void complete_FromCanceled_ThrowsException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCELED)
                    .build();

            // when, then
            assertThatThrownBy(() ->
                    payment.complete("pk_test", PaymentMethod.CARD, OffsetDateTime.now())
            ).isInstanceOf(PaymentAlreadyProcessedPaymentException.class);
        }
    }

    @Nested
    @DisplayName("cancel 메서드")
    class CancelTest {

        @Test
        @DisplayName("성공 : SUCCESS -> CANCELED 전환")
        void cancel_FromSuccess_TransitionsToCanceled() {
            // given
            Payment payment = PaymentFixture.createSuccessPayment(member);

            // when
            payment.cancel("고객 요청");

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(payment.getCancelReason()).isEqualTo("고객 요청");
        }

        @Test
        @DisplayName("성공 : PENDING -> CANCELED 전환")
        void cancel_FromPending_TransitionsToCanceled() {
            // given
            Payment payment = Payment.prepare(member, 10000L);

            // when
            payment.cancel("시간 초과");

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        @DisplayName("실패 : CANCELED 상태에서 cancel 호출")
        void cancel_FromCanceled_ThrowsException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCELED)
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.cancel("중복 취소"))
                    .isInstanceOf(PaymentNotCancellableException.class);
        }
    }

    @Nested
    @DisplayName("markCompensationCompleted 메서드")
    class MarkCompensationCompletedTest {

        @Test
        @DisplayName("성공 : compensationCompleted = true 설정")
        void markCompensationCompleted_SetsFlag() {
            // given
            Payment payment = Payment.prepare(member, 10000L);

            // when
            payment.markCompensationCompleted();

            // then
            assertThat(payment.isCompensationCompleted()).isTrue();
        }
    }

    @Nested
    @DisplayName("validateForConfirmation 메서드")
    class ValidateForConfirmationTest {

        @Test
        @DisplayName("성공 : PENDING + 올바른 소유자 + 올바른 금액")
        void validateForConfirmation_ValidConditions_NoException() {
            // given
            Payment payment = Payment.prepare(member, 10000L);

            // when, then (예외 발생 안 함)
            payment.validateForConfirmation(member, 10000L);
        }

        @Test
        @DisplayName("실패 : SUCCESS 상태")
        void validateForConfirmation_SuccessStatus_ThrowsException() {
            // given
            Payment payment = PaymentFixture.createSuccessPayment(member);

            // when, then
            assertThatThrownBy(() -> payment.validateForConfirmation(member, 10000L))
                    .isInstanceOf(PaymentAlreadyProcessedPaymentException.class);
        }

        @Test
        @DisplayName("실패 : 다른 소유자")
        void validateForConfirmation_DifferentOwner_ThrowsException() {
            // given
            Payment payment = Payment.prepare(member, 10000L);
            Member otherMember = MemberFixture.createMember(999L);

            // when, then
            assertThatThrownBy(() -> payment.validateForConfirmation(otherMember, 10000L))
                    .isInstanceOf(PaymentOwnershipMismatchException.class);
        }

        @Test
        @DisplayName("실패 : 금액 불일치")
        void validateForConfirmation_AmountMismatch_ThrowsException() {
            // given
            Payment payment = Payment.prepare(member, 10000L);

            // when, then
            assertThatThrownBy(() -> payment.validateForConfirmation(member, 5000L))
                    .isInstanceOf(PaymentAmountMismatchException.class);
        }
    }

    @Nested
    @DisplayName("validateForCancellation 메서드")
    class ValidateForCancellationTest {

        @Test
        @DisplayName("성공 : SUCCESS + 올바른 소유자 + CARD 결제")
        void validateForCancellation_ValidCardPayment_NoException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.CARD)
                    .paymentKey("pk_test")
                    .paidAt(OffsetDateTime.now())
                    .build();

            // when, then (예외 발생 안 함)
            payment.validateForCancellation(member);
        }

        @Test
        @DisplayName("실패 : PENDING 상태")
        void validateForCancellation_PendingStatus_ThrowsException() {
            // given
            Payment payment = Payment.prepare(member, 10000L);

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(member))
                    .isInstanceOf(PaymentNotCancellableException.class);
        }

        @Test
        @DisplayName("실패 : 다른 소유자")
        void validateForCancellation_DifferentOwner_ThrowsException() {
            // given
            Payment payment = PaymentFixture.createSuccessPayment(member);
            Member otherMember = MemberFixture.createMember(999L);

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(otherMember))
                    .isInstanceOf(PaymentOwnershipMismatchException.class);
        }

        @Test
        @DisplayName("실패 : PHONE 결제 + 다른 월")
        void validateForCancellation_PhonePaymentDifferentMonth_ThrowsException() {
            // given: 2달 전 결제
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.PHONE)
                    .paymentKey("pk_test")
                    .paidAt(OffsetDateTime.now().minusMonths(2))
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(member))
                    .isInstanceOf(PaymentPeriodExpiredException.class);
        }

        @Test
        @DisplayName("실패 : VIRTUAL_ACCOUNT -> 항상 취소 불가")
        void validateForCancellation_VirtualAccount_ThrowsException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.VIRTUAL_ACCOUNT)
                    .paymentKey("pk_test")
                    .paidAt(OffsetDateTime.now())
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(member))
                    .isInstanceOf(PaymentPeriodExpiredException.class);
        }

        @Test
        @DisplayName("실패 : method=null")
        void validateForCancellation_NullMethod_ThrowsException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.SUCCESS)
                    .paymentKey("pk_test")
                    .paidAt(OffsetDateTime.now())
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(member))
                    .isInstanceOf(PaymentPeriodExpiredException.class);
        }

        @Test
        @DisplayName("실패 : paidAt=null")
        void validateForCancellation_NullPaidAt_ThrowsException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.CARD)
                    .paymentKey("pk_test")
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(member))
                    .isInstanceOf(PaymentPeriodExpiredException.class);
        }
    }
}
