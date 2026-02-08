package com.ureca.snac.payment.scheduler;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentReconciliationProcessor 단위 테스트")
class PaymentReconciliationProcessorTest {

    @InjectMocks
    private PaymentReconciliationProcessor processor;

    @Mock
    private PaymentRepository paymentRepository;

    private final Member member = MemberFixture.createMember(1L);

    @Nested
    @DisplayName("cancelPayment 메서드")
    class CancelPaymentTest {

        @Test
        @DisplayName("PENDING 상태 결제 -> 취소 성공, true 반환")
        void shouldCancelPendingPayment() {
            // given
            Payment payment = PaymentFixture.builder()
                    .id(1L)
                    .member(member)
                    .status(PaymentStatus.PENDING)
                    .build();

            given(paymentRepository.findByIdForUpdate(1L)).willReturn(Optional.of(payment));

            // when
            boolean result = processor.cancelPayment(1L, "대사: 자동 취소");

            // then
            assertThat(result).isTrue();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }

        @Test
        @DisplayName("이미 SUCCESS 상태 결제 -> no-op, false 반환")
        void shouldReturnFalseForSuccessPayment() {
            // given
            Payment payment = PaymentFixture.builder()
                    .id(2L)
                    .member(member)
                    .status(PaymentStatus.SUCCESS)
                    .build();

            given(paymentRepository.findByIdForUpdate(2L)).willReturn(Optional.of(payment));

            // when
            boolean result = processor.cancelPayment(2L, "대사: 자동 취소");

            // then
            assertThat(result).isFalse();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @Test
        @DisplayName("실패 : 존재하지 않는 Payment -> PaymentNotFoundException")
        void shouldThrowWhenPaymentNotFound() {
            // given
            given(paymentRepository.findByIdForUpdate(999L)).willReturn(Optional.empty());

            // when, then
            assertThatThrownBy(() -> processor.cancelPayment(999L, "대사: 자동 취소"))
                    .isInstanceOf(PaymentNotFoundException.class);
        }

        @Test
        @DisplayName("이미 CANCELED 상태 결제 -> no-op, false 반환")
        void shouldReturnFalseForCanceledPayment() {
            // given
            Payment payment = PaymentFixture.builder()
                    .id(3L)
                    .member(member)
                    .status(PaymentStatus.CANCELED)
                    .build();

            given(paymentRepository.findByIdForUpdate(3L)).willReturn(Optional.of(payment));

            // when
            boolean result = processor.cancelPayment(3L, "대사: 자동 취소");

            // then
            assertThat(result).isFalse();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }
    }
}
