package com.ureca.snac.support.fixture;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentMethod;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.support.TestReflectionUtils;

import java.time.OffsetDateTime;

// Payment 테스트 Fixture
public class PaymentFixture {

    private static final String DEFAULT_ORDER_ID = "snac_order_test_";
    private static final Long DEFAULT_AMOUNT = 10000L;

    public static PaymentBuilder builder() {
        return new PaymentBuilder();
    }

    public static Payment createPendingPayment(Member member) {
        return builder().member(member).status(PaymentStatus.PENDING).build();
    }

    public static Payment createSuccessPayment(Member member) {
        return builder()
                .member(member)
                .status(PaymentStatus.SUCCESS)
                .method(PaymentMethod.CARD)
                .paymentKey("test_payment_key_" + System.currentTimeMillis())
                .paidAt(OffsetDateTime.now())
                .build();
    }

    public static class PaymentBuilder {
        private Long id;
        private Member member;
        private String orderId = DEFAULT_ORDER_ID + System.currentTimeMillis();
        private Long amount = DEFAULT_AMOUNT;
        private PaymentStatus status = PaymentStatus.PENDING;
        private PaymentMethod method;
        private String paymentKey;
        private OffsetDateTime paidAt;

        public PaymentBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public PaymentBuilder member(Member member) {
            this.member = member;
            return this;
        }

        public PaymentBuilder orderId(String orderId) {
            this.orderId = orderId;
            return this;
        }

        public PaymentBuilder amount(Long amount) {
            this.amount = amount;
            return this;
        }

        public PaymentBuilder status(PaymentStatus status) {
            this.status = status;
            return this;
        }

        public PaymentBuilder method(PaymentMethod method) {
            this.method = method;
            return this;
        }

        public PaymentBuilder paymentKey(String paymentKey) {
            this.paymentKey = paymentKey;
            return this;
        }

        public PaymentBuilder paidAt(OffsetDateTime paidAt) {
            this.paidAt = paidAt;
            return this;
        }

        public Payment build() {
            if (member == null) {
                member = MemberFixture.createMember(1L);
            }

            Payment payment = Payment.prepare(member, amount);

            TestReflectionUtils.setField(payment, "orderId", orderId);
            TestReflectionUtils.setField(payment, "status", status);

            if (id != null) {
                TestReflectionUtils.setField(payment, "id", id);
            }
            if (method != null) {
                TestReflectionUtils.setField(payment, "method", method);
            }
            if (paymentKey != null) {
                TestReflectionUtils.setField(payment, "paymentKey", paymentKey);
            }
            if (paidAt != null) {
                TestReflectionUtils.setField(payment, "paidAt", paidAt);
            }

            return payment;
        }
    }
}
