package com.ureca.snac.infra;

import com.ureca.snac.infra.dto.response.TossCancelResponse;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.infra.dto.response.TossPaymentInquiryResponse;
import com.ureca.snac.infra.fixture.TossResponseFixture;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.exception.TossRetryableException;
import com.ureca.snac.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * TossPaymentsAdapter 단위 테스트
 * <p>
 * confirmPayment: 결제 승인 (@Retryable 동작 검증)
 * cancelPayment: 결제 취소 (@Retryable 동작 검증)
 */
@DisplayName("TossPaymentsAdapterTest 단위 테스트")
class TossPaymentsAdapterTest extends IntegrationTestSupport {

    @Autowired
    private PaymentGatewayAdapter paymentGatewayAdapter;

    @MockitoBean
    private TossPaymentsClient tossPaymentsClient;

    private static final String PAYMENT_KEY = "test_payment_key";
    private static final String ORDER_ID = "snac_order_test_123";
    private static final Long AMOUNT = 10000L;

    @Nested
    @DisplayName("confirmPayment 메서드")
    class ConfirmPaymentTest {

        @Nested
        @DisplayName("재시도 동작")
        class RetryBehaviorTest {

            @Test
            @DisplayName("정상 : TossRetryableException 발생 시 최대 3회 재시도")
            void confirmPayment_ShouldRetryOnTossRetryableException() {
                // given: 모든 호출에서 TossRetryableException 발생
                given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossRetryableException(TossErrorCode.SERVICE_UNAVAILABLE));

                // when & then: 예외 발생하며 3회 호출됨
                assertThatThrownBy(() ->
                        paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT)
                ).isInstanceOf(TossRetryableException.class);

                verify(tossPaymentsClient, times(3)).confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);
            }

            @Test
            @DisplayName("정상 : 2회 실패 후 3회차에 성공하면 정상 응답 반환")
            void confirmPayment_ShouldSucceedOnThirdAttempt() {
                // given: 2번 실패 후 3번째 성공
                TossConfirmResponse successResponse = TossResponseFixture.createConfirmResponse(PAYMENT_KEY);

                given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossRetryableException(TossErrorCode.PROVIDER_ERROR))
                        .willThrow(new TossRetryableException(TossErrorCode.PROVIDER_ERROR))
                        .willReturn(successResponse);

                // when
                TossConfirmResponse response = paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);

                // then: 성공 응답 반환, 총 3회 호출
                assertThat(response).isEqualTo(successResponse);
                verify(tossPaymentsClient, times(3)).confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);
            }

            @Test
            @DisplayName("예외 : 재시도 불가능한 예외는 즉시 전파")
            void confirmPayment_ShouldNotRetryOnNonRetryableException() {
                // given: RuntimeException은 재시도 대상이 아님
                given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new RuntimeException("Non-retryable error"));

                // when & then: 예외 발생하며 1회만 호출됨
                assertThatThrownBy(() ->
                        paymentGatewayAdapter.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT)
                ).isInstanceOf(RuntimeException.class);

                verify(tossPaymentsClient, times(1)).confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);
            }
        }
    }

    @Nested
    @DisplayName("cancelPayment 메서드")
    class CancelPaymentTest {

        private static final String CANCEL_REASON = "Auto-cancel: DB 처리 실패";

        @Nested
        @DisplayName("재시도 동작")
        class RetryBehaviorTest {

            @Test
            @DisplayName("정상 : TossRetryableException 발생 시 최대 3회 재시도")
            void cancelPayment_ShouldRetryOnTossRetryableException() {
                // given: 모든 호출에서 TossRetryableException 발생
                given(tossPaymentsClient.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                        .willThrow(new TossRetryableException(TossErrorCode.SERVICE_UNAVAILABLE));

                // when & then: 예외 발생하며 3회 호출됨
                assertThatThrownBy(() ->
                        paymentGatewayAdapter.cancelPayment(PAYMENT_KEY, CANCEL_REASON)
                ).isInstanceOf(TossRetryableException.class);

                verify(tossPaymentsClient, times(3)).cancelPayment(PAYMENT_KEY, CANCEL_REASON);
            }

            @Test
            @DisplayName("정상 : 2회 실패 후 3회차에 성공하면 정상 응답 반환")
            void cancelPayment_ShouldSucceedOnThirdAttempt() {
                // given: 2번 실패 후 3번째 성공
                TossCancelResponse tossCancelResponse = TossResponseFixture.createCancelResponse(
                        PAYMENT_KEY, AMOUNT, CANCEL_REASON);

                given(tossPaymentsClient.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                        .willThrow(new TossRetryableException(TossErrorCode.PROVIDER_ERROR))
                        .willThrow(new TossRetryableException(TossErrorCode.PROVIDER_ERROR))
                        .willReturn(tossCancelResponse);

                // when
                PaymentCancelResponse response = paymentGatewayAdapter.cancelPayment(PAYMENT_KEY, CANCEL_REASON);

                // then: 성공 응답 반환, 총 3회 호출
                assertThat(response).isNotNull();
                assertThat(response.paymentKey()).isEqualTo(PAYMENT_KEY);
                verify(tossPaymentsClient, times(3)).cancelPayment(PAYMENT_KEY, CANCEL_REASON);
            }

            @Test
            @DisplayName("예외 : 재시도 불가능한 예외는 즉시 전파")
            void cancelPayment_ShouldNotRetryOnNonRetryableException() {
                // given: RuntimeException은 재시도 대상이 아님
                given(tossPaymentsClient.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                        .willThrow(new RuntimeException("Non-retryable error"));

                // when & then: 예외 발생하며 1회만 호출됨
                assertThatThrownBy(() ->
                        paymentGatewayAdapter.cancelPayment(PAYMENT_KEY, CANCEL_REASON)
                ).isInstanceOf(RuntimeException.class);

                verify(tossPaymentsClient, times(1)).cancelPayment(PAYMENT_KEY, CANCEL_REASON);
            }
        }
    }

    @Nested
    @DisplayName("inquirePaymentByOrderId 메서드")
    class InquirePaymentByOrderIdTest {

        @Nested
        @DisplayName("재시도 동작")
        class RetryBehaviorTest {

            @Test
            @DisplayName("정상 : TossRetryableException 발생 시 최대 3회 재시도")
            void inquire_ShouldRetryOnTossRetryableException() {
                // given: 모든 호출에서 TossRetryableException 발생
                given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID))
                        .willThrow(new TossRetryableException(TossErrorCode.SERVICE_UNAVAILABLE));

                // when & then: 예외 발생하며 3회 호출됨
                assertThatThrownBy(() ->
                        paymentGatewayAdapter.inquirePaymentByOrderId(ORDER_ID)
                ).isInstanceOf(TossRetryableException.class);

                verify(tossPaymentsClient, times(3)).inquirePaymentByOrderId(ORDER_ID);
            }

            @Test
            @DisplayName("정상 : 2회 실패 후 3회차에 성공")
            void inquire_ShouldSucceedOnThirdAttempt() {
                // given: 2번 실패 후 3번째 성공
                TossPaymentInquiryResponse successResponse = new TossPaymentInquiryResponse(
                        "pk_test", ORDER_ID, "DONE", "카드", AMOUNT, OffsetDateTime.now()
                );

                given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID))
                        .willThrow(new TossRetryableException(TossErrorCode.PROVIDER_ERROR))
                        .willThrow(new TossRetryableException(TossErrorCode.PROVIDER_ERROR))
                        .willReturn(successResponse);

                // when
                TossPaymentInquiryResponse response = paymentGatewayAdapter.inquirePaymentByOrderId(ORDER_ID);

                // then
                assertThat(response).isEqualTo(successResponse);
                verify(tossPaymentsClient, times(3)).inquirePaymentByOrderId(ORDER_ID);
            }

            @Test
            @DisplayName("예외 : 재시도 불가능한 예외는 즉시 전파")
            void inquire_ShouldNotRetryOnNonRetryableException() {
                // given: RuntimeException은 재시도 대상이 아님
                given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID))
                        .willThrow(new RuntimeException("Non-retryable error"));

                // when & then: 예외 발생하며 1회만 호출됨
                assertThatThrownBy(() ->
                        paymentGatewayAdapter.inquirePaymentByOrderId(ORDER_ID)
                ).isInstanceOf(RuntimeException.class);

                verify(tossPaymentsClient, times(1)).inquirePaymentByOrderId(ORDER_ID);
            }
        }
    }
}
