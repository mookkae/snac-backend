package com.ureca.snac.support.fixture;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentMethod;
import com.ureca.snac.payment.entity.PaymentStatus;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;

/**
 * Payment 테스트 Fixture
 * 리플렉션을 사용하여 운영 코드와 분리
 */
public class PaymentFixture {

    private static final String DEFAULT_ORDER_ID = "snac_order_test_";
    private static final Long DEFAULT_AMOUNT = 10000L;

    public static PaymentBuilder builder() {
        return new PaymentBuilder();
    }

    // PENDING 상태 기본 Payment
    public static Payment createPendingPayment(Member member) {
        return builder()
                .member(member)
                .status(PaymentStatus.PENDING)
                .build();
    }

    // SUCCESS 상태 Payment (취소 테스트용)
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

            setField(payment, "orderId", orderId);
            setField(payment, "status", status);

            if (id != null) {
                setField(payment, "id", id);
            }
            if (method != null) {
                setField(payment, "method", method);
            }
            if (paymentKey != null) {
                setField(payment, "paymentKey", paymentKey);
            }
            if (paidAt != null) {
                setField(payment, "paidAt", paidAt);
            }

            return payment;
        }

        private void setField(Object target, String fieldName, Object value) {
            try {
                Field field = getField(target.getClass(), fieldName);
                field.setAccessible(true);
                field.set(target, value);
            } catch (Exception e) {
                throw new RuntimeException("필드 설정 실패: " + fieldName, e);
            }
        }

        private Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                Class<?> superClass = clazz.getSuperclass();
                if (superClass != null) {
                    return getField(superClass, fieldName);
                }
                throw e;
            }
        }
    }
}
