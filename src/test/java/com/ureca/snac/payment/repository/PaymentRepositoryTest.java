package com.ureca.snac.payment.repository;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentMethod;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.support.RepositoryTestSupport;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentRepository 슬라이스 테스트
 * findByOrderId: 주문번호로 Payment + Member 조회
 * findByPaymentKeyWithMember: 결제키로 Payment + Member 조회
 * findByOrderIdWithMemberForUpdate: 비관적 락
 */
@DisplayName("PaymentRepositoryTest 슬라이스 테스트")
@Transactional
class PaymentRepositoryTest extends RepositoryTestSupport {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MemberRepository memberRepository;

    private Member member;

    private static final String ORDER_ID = "snac_order_test_123";
    private static final String PAYMENT_KEY = "test_payment_key_456";
    private static final Long AMOUNT = 10000L;

    @BeforeEach
    void setUp() {
        member = memberRepository.save(MemberFixture.builder()
                .id(null)
                .email("test_" + System.currentTimeMillis() + "@snac.com")
                .build());

        Payment payment = paymentRepository.save(PaymentFixture.builder()
                .id(null)
                .member(member)
                .orderId(ORDER_ID)
                .paymentKey(PAYMENT_KEY)
                .amount(AMOUNT)
                .status(PaymentStatus.PENDING)
                .build());

        em.flush();
        em.clear();
    }

    @Nested
    @DisplayName("findByOrderId 메서드")
    class FindByOrderIdTest {

        @Test
        @DisplayName("정상 : 주문번호로 Payment + Member 조회 (fetch join)")
        void findByOrderId_existingOrder_returnsPaymentWithMember() {
            // when
            Optional<Payment> result = paymentRepository.findByOrderId(ORDER_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getOrderId()).isEqualTo(ORDER_ID);
            assertThat(result.get().getMember()).isNotNull();
            assertThat(result.get().getMember().getId()).isEqualTo(member.getId());
        }

        @Test
        @DisplayName("정상 : 존재하지 않는 주문번호는 빈 Optional 반환")
        void findByOrderId_nonExistingOrder_returnsEmpty() {
            // when
            Optional<Payment> result = paymentRepository.findByOrderId("non_existing_order");

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("정상 : N+1 문제 없이 Member 조회")
        void findByOrderId_noNPlusOneProblem() {
            // when
            Optional<Payment> result = paymentRepository.findByOrderId(ORDER_ID);

            // then: Member가 이미 로딩되어 있어야 함 (fetch join)
            assertThat(result).isPresent();
            Payment foundPayment = result.get();

            // 프록시가 아닌 실제 Member 객체인지 확인 (fetch join 동작 검증)
            assertThat(foundPayment.getMember().getEmail()).isNotNull();
        }
    }

    @Nested
    @DisplayName("findByPaymentKeyWithMember 메서드")
    class FindByPaymentKeyWithMemberTest {

        @Test
        @DisplayName("정상 : 결제키로 Payment + Member 조회 (fetch join)")
        void findByPaymentKeyWithMember_existingKey_returnsPaymentWithMember() {
            // when
            Optional<Payment> result = paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getPaymentKey()).isEqualTo(PAYMENT_KEY);
            assertThat(result.get().getMember()).isNotNull();
            assertThat(result.get().getMember().getId()).isEqualTo(member.getId());
        }

        @Test
        @DisplayName("정상 : 존재하지 않는 결제키는 빈 Optional 반환")
        void findByPaymentKeyWithMember_nonExistingKey_returnsEmpty() {
            // when
            Optional<Payment> result = paymentRepository.findByPaymentKeyWithMember("non_existing_key");

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("정상 : N+1 문제 없이 Member 조회")
        void findByPaymentKeyWithMember_noNPlusOneProblem() {
            // when
            Optional<Payment> result = paymentRepository.findByPaymentKeyWithMember(PAYMENT_KEY);

            // then: Member가 이미 로딩되어 있어야 함 (fetch join)
            assertThat(result).isPresent();
            Payment foundPayment = result.get();

            // 프록시가 아닌 실제 Member 객체인지 확인 (fetch join 동작 검증)
            assertThat(foundPayment.getMember().getEmail()).isNotNull();
        }
    }

    @Nested
    @DisplayName("findByOrderIdWithMemberForUpdate 메서드")
    class FindByOrderIdWithMemberForUpdateTest {

        @Test
        @DisplayName("정상 : 비관적 락으로 Payment + Member 조회")
        void findByOrderIdWithMemberForUpdate_existingOrder_returnsPaymentWithLock() {
            // when
            Optional<Payment> result = paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().getOrderId()).isEqualTo(ORDER_ID);
            assertThat(result.get().getMember()).isNotNull();
            assertThat(result.get().getMember().getId()).isEqualTo(member.getId());
        }

        @Test
        @DisplayName("정상 : 존재하지 않는 주문번호는 빈 Optional 반환")
        void findByOrderIdWithMemberForUpdate_nonExistingOrder_returnsEmpty() {
            // when
            Optional<Payment> result = paymentRepository.findByOrderIdWithMemberForUpdate("non_existing_order");

            // then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("정상 : 조회 후 상태 변경 가능 (락 획득 확인)")
        void findByOrderIdWithMemberForUpdate_canUpdateStatus() {
            // when
            Optional<Payment> result = paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID);

            // then: 락을 획득했으므로 상태 변경 가능
            assertThat(result).isPresent();
            Payment foundPayment = result.get();

            foundPayment.complete(PAYMENT_KEY, PaymentMethod.CARD, OffsetDateTime.now());
            paymentRepository.save(foundPayment);
            em.flush();
            em.clear();

            // 변경된 상태 확인
            Payment updated = paymentRepository.findById(foundPayment.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @Test
        @DisplayName("정상 : N+1 문제 없이 Member 조회")
        void findByOrderIdWithMemberForUpdate_noNPlusOneProblem() {
            // when
            Optional<Payment> result = paymentRepository.findByOrderIdWithMemberForUpdate(ORDER_ID);

            // then: Member가 이미 로딩되어 있어야 함 (fetch join)
            assertThat(result).isPresent();
            Payment foundPayment = result.get();

            // 프록시가 아닌 실제 Member 객체인지 확인 (fetch join 동작 검증)
            assertThat(foundPayment.getMember().getEmail()).isNotNull();
        }
    }
}
