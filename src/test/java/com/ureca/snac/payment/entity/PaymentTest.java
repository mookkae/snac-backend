package com.ureca.snac.payment.entity;

import com.ureca.snac.common.exception.BusinessException;
import com.ureca.snac.common.exception.InternalServerException;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.exception.*;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
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
            ).isInstanceOf(PaymentAlreadySuccessException.class);
        }

        @Test
        @DisplayName("실패 : CANCELED 상태에서 complete 호출 -> AlreadyCanceledPaymentException")
        void complete_FromCanceled_ThrowsException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCELED)
                    .build();

            // when, then
            assertThatThrownBy(() ->
                    payment.complete("pk_test", PaymentMethod.CARD, OffsetDateTime.now())
            ).isInstanceOf(AlreadyCanceledPaymentException.class);
        }

        @Test
        @DisplayName("실패 : CANCEL_REQUESTED 상태에서 complete 호출 -> PaymentCancellationInProgressException")
        void complete_FromCancelRequested_ThrowsException() {
            // given: 취소 진행 중인 결제 — complete 불가
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCEL_REQUESTED)
                    .build();

            // when, then
            assertThatThrownBy(() ->
                    payment.complete("pk_test", PaymentMethod.CARD, OffsetDateTime.now())
            ).isInstanceOf(PaymentCancellationInProgressException.class);
        }
    }

    @Nested
    @DisplayName("requestCancellation 메서드")
    class RequestCancellationTest {

        @Test
        @DisplayName("성공 : SUCCESS -> CANCEL_REQUESTED 전환")
        void requestCancellation_FromSuccess_TransitionsToCancelRequested() {
            // given
            Payment payment = PaymentFixture.createSuccessPayment(member);

            // when
            payment.requestCancellation();

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCEL_REQUESTED);
        }

        @Test
        @DisplayName("실패 : PENDING 상태에서 requestCancellation 호출")
        void requestCancellation_FromPending_ThrowsException() {
            // given
            Payment payment = Payment.prepare(member, 10000L);

            // when, then
            assertThatThrownBy(() -> payment.requestCancellation())
                    .isInstanceOf(PaymentNotCancellableException.class);
        }

        @Test
        @DisplayName("실패 : CANCELED 상태에서 requestCancellation 호출")
        void requestCancellation_FromCanceled_ThrowsException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCELED)
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.requestCancellation())
                    .isInstanceOf(PaymentNotCancellableException.class);
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
        @DisplayName("성공 : CANCEL_REQUESTED -> CANCELED 전환")
        void cancel_FromCancelRequested_TransitionsToCanceled() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCEL_REQUESTED)
                    .build();

            // when
            payment.cancel("대사 취소");

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
            assertThat(payment.getCancelReason()).isEqualTo("대사 취소");
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
    @DisplayName("revertCancellation 메서드")
    class RevertCancellationTest {

        @Test
        @DisplayName("성공 : CANCEL_REQUESTED -> SUCCESS 복구")
        void revertCancellation_FromCancelRequested_TransitionsToSuccess() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCEL_REQUESTED)
                    .build();

            // when
            payment.revertCancellation();

            // then
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @Test
        @DisplayName("실패 : CANCEL_REQUESTED가 아닌 상태에서 호출 -> InternalServerException (코드 버그)")
        void revertCancellation_FromNonCancelRequested_ThrowsInternalServerException() {
            // given: revertCancellation은 PG NOT_CANCELABLE_PAYMENT 전용 경로
            // CANCEL_REQUESTED 이외 상태 진입은 호출 측 코드 버그이므로 500 예외
            Payment payment = PaymentFixture.createSuccessPayment(member);

            // when, then
            assertThatThrownBy(() -> payment.revertCancellation())
                    .isInstanceOf(InternalServerException.class);
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
            payment.validateForConfirmation(member.getId(), 10000L);
        }

        @Test
        @DisplayName("실패 : SUCCESS 상태")
        void validateForConfirmation_SuccessStatus_ThrowsException() {
            // given
            Payment payment = PaymentFixture.createSuccessPayment(member);

            // when, then
            assertThatThrownBy(() -> payment.validateForConfirmation(member.getId(), 10000L))
                    .isInstanceOf(PaymentAlreadySuccessException.class);
        }

        @Test
        @DisplayName("실패 : CANCELED 상태 -> AlreadyCanceledPaymentException")
        void validateForConfirmation_CanceledStatus_ThrowsException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCELED)
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.validateForConfirmation(member.getId(), 10000L))
                    .isInstanceOf(AlreadyCanceledPaymentException.class);
        }

        @Test
        @DisplayName("실패 : CANCEL_REQUESTED 상태 -> PaymentCancellationInProgressException")
        void validateForConfirmation_CancelRequestedStatus_ThrowsException() {
            // given: 취소 진행 중인 결제에 중복 확정 시도 — race condition 방어
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCEL_REQUESTED)
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.validateForConfirmation(member.getId(), 10000L))
                    .isInstanceOf(PaymentCancellationInProgressException.class);
        }

        @Test
        @DisplayName("실패 : 다른 소유자")
        void validateForConfirmation_DifferentOwner_ThrowsException() {
            // given
            Payment payment = Payment.prepare(member, 10000L);
            Member otherMember = MemberFixture.createMember(999L);

            // when, then
            assertThatThrownBy(() -> payment.validateForConfirmation(otherMember.getId(), 10000L))
                    .isInstanceOf(PaymentOwnershipMismatchException.class);
        }

        @Test
        @DisplayName("실패 : 금액 불일치")
        void validateForConfirmation_AmountMismatch_ThrowsException() {
            // given
            Payment payment = Payment.prepare(member, 10000L);

            // when, then
            assertThatThrownBy(() -> payment.validateForConfirmation(member.getId(), 5000L))
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
            payment.validateForCancellation(member.getId(), Clock.systemDefaultZone());
        }

        @Test
        @DisplayName("실패 : PENDING 상태 -> PaymentNotCancellableException")
        void validateForCancellation_PendingStatus_ThrowsException() {
            // given
            Payment payment = Payment.prepare(member, 10000L);

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(member.getId(), Clock.systemDefaultZone()))
                    .isInstanceOf(PaymentNotCancellableException.class);
        }

        @Test
        @DisplayName("실패 : CANCEL_REQUESTED 상태 -> PaymentCancellationInProgressException")
        void validateForCancellation_CancelRequestedStatus_ThrowsException() {
            // given: 이미 취소 진행 중 — 중복 취소 시도
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCEL_REQUESTED)
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(member.getId(), Clock.systemDefaultZone()))
                    .isInstanceOf(PaymentCancellationInProgressException.class);
        }

        @Test
        @DisplayName("실패 : CANCELED 상태 -> AlreadyCanceledPaymentException")
        void validateForCancellation_CanceledStatus_ThrowsException() {
            // given: 이미 취소 완료된 결제 — 중복 취소 시도
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.CANCELED)
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(member.getId(), Clock.systemDefaultZone()))
                    .isInstanceOf(AlreadyCanceledPaymentException.class);
        }

        @Test
        @DisplayName("실패 : UNKNOWN method -> PaymentMethodNotCancelableException")
        void validateForCancellation_UnknownMethod_ThrowsMethodNotCancelableException() {
            // given: Toss 미지원 결제수단 — 기간 만료(PaymentPeriodExpiredException)가 아닌 수단 불가로 응답해야 함
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.UNKNOWN)
                    .paymentKey("pk_test")
                    .paidAt(OffsetDateTime.now())
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(member.getId(), Clock.systemDefaultZone()))
                    .isInstanceOf(PaymentMethodNotCancelableException.class);
        }

        @Test
        @DisplayName("실패 : 다른 소유자")
        void validateForCancellation_DifferentOwner_ThrowsException() {
            // given
            Payment payment = PaymentFixture.createSuccessPayment(member);
            Member otherMember = MemberFixture.createMember(999L);

            // when, then
            assertThatThrownBy(() -> payment.validateForCancellation(otherMember.getId(), Clock.systemDefaultZone()))
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
            assertThatThrownBy(() -> payment.validateForCancellation(member.getId(), Clock.systemDefaultZone()))
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

            // when, then: 결제 수단 취소 불가 → 기간 만료보다 먼저 검증
            assertThatThrownBy(() -> payment.validateForCancellation(member.getId(), Clock.systemDefaultZone()))
                    .isInstanceOf(PaymentMethodNotCancelableException.class);
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

            // when, then: method=null → isMethodCancelable() false → PaymentMethodNotCancelableException
            assertThatThrownBy(() -> payment.validateForCancellation(member.getId(), Clock.systemDefaultZone()))
                    .isInstanceOf(PaymentMethodNotCancelableException.class);
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
            assertThatThrownBy(() -> payment.validateForCancellation(member.getId(), Clock.systemDefaultZone()))
                    .isInstanceOf(PaymentPeriodExpiredException.class);
        }

        @Test
        @DisplayName("성공 : PHONE 결제 + 같은 월 -> 취소 가능")
        void validateForCancellation_PhonePaymentSameMonth_NoException() {
            // given: 이번 달 결제
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.PHONE)
                    .paymentKey("pk_test")
                    .paidAt(OffsetDateTime.now())
                    .build();

            // when, then (예외 발생 안 함)
            payment.validateForCancellation(member.getId(), Clock.systemDefaultZone());
        }
    }

    @Nested
    @DisplayName("validateCancellationPeriodNotExpired 메서드")
    class ValidateCancellationPeriodNotExpiredTest {

        @Test
        @DisplayName("성공 : CARD 결제 + paidAt 존재 -> 만료 아님, 예외 없음")
        void validateCancellationPeriodNotExpired_CardPayment_NoException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.CARD)
                    .paymentKey("pk_test")
                    .paidAt(OffsetDateTime.now())
                    .build();

            // when, then
            payment.validateCancellationPeriodNotExpired(Clock.systemDefaultZone());
        }

        @Test
        @DisplayName("실패 : PHONE 결제 + 다른 월 -> 만료, PaymentPeriodExpiredException")
        void validateCancellationPeriodNotExpired_PhonePaymentExpired_ThrowsException() {
            // given
            Payment payment = PaymentFixture.builder()
                    .member(member)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.PHONE)
                    .paymentKey("pk_test")
                    .paidAt(OffsetDateTime.now().minusMonths(2))
                    .build();

            // when, then
            assertThatThrownBy(() -> payment.validateCancellationPeriodNotExpired(Clock.systemDefaultZone()))
                    .isInstanceOf(PaymentPeriodExpiredException.class);
        }
    }
}
