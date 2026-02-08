package com.ureca.snac.payment.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PaymentMethod 단위 테스트")
class PaymentMethodTest {

    @Nested
    @DisplayName("fromTossMethod 메서드")
    class FromTossMethodTest {

        @Test
        @DisplayName("성공 : 카드 -> CARD")
        void fromTossMethod_Card_ReturnsCard() {
            assertThat(PaymentMethod.fromTossMethod("카드")).isEqualTo(PaymentMethod.CARD);
        }

        @Test
        @DisplayName("성공 : 휴대폰 -> PHONE")
        void fromTossMethod_Phone_ReturnsPhone() {
            assertThat(PaymentMethod.fromTossMethod("휴대폰")).isEqualTo(PaymentMethod.PHONE);
        }

        @Test
        @DisplayName("성공 : 가상계좌 -> VIRTUAL_ACCOUNT")
        void fromTossMethod_VirtualAccount_ReturnsVirtualAccount() {
            assertThat(PaymentMethod.fromTossMethod("가상계좌")).isEqualTo(PaymentMethod.VIRTUAL_ACCOUNT);
        }

        @Test
        @DisplayName("성공 : 계좌이체 -> TRANSFER")
        void fromTossMethod_Transfer_ReturnsTransfer() {
            assertThat(PaymentMethod.fromTossMethod("계좌이체")).isEqualTo(PaymentMethod.TRANSFER);
        }

        @Test
        @DisplayName("성공 : 간편결제 -> EASY_PAY")
        void fromTossMethod_EasyPay_ReturnsEasyPay() {
            assertThat(PaymentMethod.fromTossMethod("간편결제")).isEqualTo(PaymentMethod.EASY_PAY);
        }

        @Test
        @DisplayName("성공 : null -> UNKNOWN")
        void fromTossMethod_Null_ReturnsUnknown() {
            assertThat(PaymentMethod.fromTossMethod(null)).isEqualTo(PaymentMethod.UNKNOWN);
        }

        @Test
        @DisplayName("성공 : 알 수 없는 문자열 -> UNKNOWN")
        void fromTossMethod_UnknownString_ReturnsUnknown() {
            assertThat(PaymentMethod.fromTossMethod("비트코인")).isEqualTo(PaymentMethod.UNKNOWN);
        }
    }
}
