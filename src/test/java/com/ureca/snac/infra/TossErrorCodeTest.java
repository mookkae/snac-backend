package com.ureca.snac.infra;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TossErrorCode 단위 테스트")
class TossErrorCodeTest {

    @Nested
    @DisplayName("fromCode 메서드")
    class FromCodeTest {

        @Test
        @DisplayName("성공 : SERVICE_UNAVAILABLE 코드 -> retryable=true")
        void fromCode_ServiceUnavailable_ReturnsRetryable() {
            TossErrorCode result = TossErrorCode.fromCode("SERVICE_UNAVAILABLE");

            assertThat(result).isEqualTo(TossErrorCode.SERVICE_UNAVAILABLE);
            assertThat(result.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("성공 : INVALID_CARD_NUMBER -> retryable=false")
        void fromCode_InvalidCardNumber_ReturnsNonRetryable() {
            TossErrorCode result = TossErrorCode.fromCode("INVALID_CARD_NUMBER");

            assertThat(result).isEqualTo(TossErrorCode.INVALID_CARD_NUMBER);
            assertThat(result.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("성공 : ALREADY_PROCESSED_PAYMENT")
        void fromCode_AlreadyProcessedPayment() {
            TossErrorCode result = TossErrorCode.fromCode("ALREADY_PROCESSED_PAYMENT");

            assertThat(result).isEqualTo(TossErrorCode.ALREADY_PROCESSED_PAYMENT);
            assertThat(result.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("성공 : 알 수 없는 코드 -> UNKNOWN (retryable=true)")
        void fromCode_UnknownCode_ReturnsUnknown() {
            TossErrorCode result = TossErrorCode.fromCode("COMPLETELY_UNKNOWN_CODE");

            assertThat(result).isEqualTo(TossErrorCode.UNKNOWN);
            assertThat(result.isRetryable()).isTrue();
        }

        @Test
        @DisplayName("성공 : null 입력 -> UNKNOWN")
        void fromCode_NullInput_ReturnsUnknown() {
            TossErrorCode result = TossErrorCode.fromCode(null);

            assertThat(result).isEqualTo(TossErrorCode.UNKNOWN);
        }
    }
}
