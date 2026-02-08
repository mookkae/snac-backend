package com.ureca.snac.payment.mapper;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.infra.dto.response.TossCancelResponse;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PaymentCancelMapper 단위 테스트
 * 가장 최근 취소 내역 매핑 검증
 */
@DisplayName("PaymentCancelMapperTest 단위 테스트")
class PaymentCancelMapperTest {

    private final PaymentCancelMapper mapper = new PaymentCancelMapper();

    private static final String PAYMENT_KEY = "test_payment_key";
    private static final String ORDER_ID = "snac_order_test_123";
    private static final Long AMOUNT = 10000L;

    @Test
    @DisplayName("정상 : 단일 취소 건 - 최근 취소 내역 반환")
    void singleCancel_ReturnsLatest() {
        // given
        TossCancelResponse.Cancel cancel = new TossCancelResponse.Cancel(
                AMOUNT, "고객 요청", OffsetDateTime.now()
        );
        TossCancelResponse response = new TossCancelResponse(PAYMENT_KEY, ORDER_ID, List.of(cancel));

        // when
        PaymentCancelResponse result = mapper.toPaymentCancelResponse(response);

        // then
        assertThat(result.paymentKey()).isEqualTo(PAYMENT_KEY);
        assertThat(result.canceledAmount()).isEqualTo(AMOUNT);
        assertThat(result.reason()).isEqualTo("고객 요청");
    }

    @Test
    @DisplayName("정상 : 여러 취소 건 - 가장 마지막 취소 내역 반환")
    void multipleCancels_ReturnsLatest() {
        // given
        TossCancelResponse.Cancel cancel5000 = new TossCancelResponse.Cancel(
                5000L, "부분 취소", OffsetDateTime.now().minusHours(1)
        );
        TossCancelResponse.Cancel cancel10000 = new TossCancelResponse.Cancel(
                AMOUNT, "전액 취소", OffsetDateTime.now()
        );
        TossCancelResponse response = new TossCancelResponse(
                PAYMENT_KEY, ORDER_ID, List.of(cancel5000, cancel10000)
        );

        // when
        PaymentCancelResponse result = mapper.toPaymentCancelResponse(response);

        // then
        assertThat(result.canceledAmount()).isEqualTo(AMOUNT);
        assertThat(result.reason()).isEqualTo("전액 취소");
    }

    @Test
    @DisplayName("예외 : 취소 내역이 비어있으면 예외")
    void emptyCancels_ThrowsException() {
        // given
        TossCancelResponse response = new TossCancelResponse(PAYMENT_KEY, ORDER_ID, List.of());

        // when, then
        assertThatThrownBy(() -> mapper.toPaymentCancelResponse(response))
                .isInstanceOf(ExternalApiException.class);
    }

    @Test
    @DisplayName("예외 : null 응답이면 예외")
    void nullResponse_ThrowsException() {
        // when, then
        assertThatThrownBy(() -> mapper.toPaymentCancelResponse(null))
                .isInstanceOf(ExternalApiException.class);
    }
}
