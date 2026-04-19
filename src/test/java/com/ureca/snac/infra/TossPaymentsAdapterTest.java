package com.ureca.snac.infra;

import com.ureca.snac.common.exception.ExternalApiException;
import com.ureca.snac.infra.dto.response.TossCancelResponse;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.infra.dto.response.TossInquiryResponse;
import com.ureca.snac.infra.exception.*;
import com.ureca.snac.infra.fixture.TossResponseFixture;
import com.ureca.snac.payment.exception.AlreadyCanceledPaymentException;
import com.ureca.snac.payment.exception.PaymentAlreadySuccessException;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.dto.GatewayPaymentStatus;
import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.payment.port.out.dto.PaymentInquiryResult;
import com.ureca.snac.payment.port.out.exception.GatewayNotCancelableException;
import com.ureca.snac.payment.port.out.exception.GatewayTransientException;
import com.ureca.snac.payment.port.out.exception.InsufficientCardBalanceException;
import com.ureca.snac.payment.port.out.exception.InvalidPaymentCardException;
import com.ureca.snac.support.IntegrationTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static com.ureca.snac.common.BaseCode.PAYMENT_GATEWAY_CONFIG_ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("TossPaymentsAdapterTest 단위 테스트")
class TossPaymentsAdapterTest extends IntegrationTestSupport {

    @Autowired
    private PaymentGatewayPort paymentGatewayPort;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private TossPaymentsClient tossPaymentsClient;

    private static final String PAYMENT_KEY = "test_payment_key";
    private static final String ORDER_ID = "snac_order_test_123";
    private static final Long AMOUNT = 10000L;

    @Nested
    @DisplayName("confirmPayment 메서드")
    class ConfirmPaymentTest {

        @Nested
        @DisplayName("재시도 및 예외 변환")
        class ExceptionMappingTest {

            @Test
            @DisplayName("정상 : TossRetryableException 발생 시 최대 3회 재시도 후 GatewayTransientException 전파")
            void confirmPayment_ShouldRetryOnTossRetryableException() {
                given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossRetryableException());

                assertThatThrownBy(() ->
                        paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT)
                ).isInstanceOf(GatewayTransientException.class);

                verify(tossPaymentsClient, times(3)).confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);
            }

            @Test
            @DisplayName("예외 : 이미 처리된 결제(TossAlreadyProcessedPaymentException) -> PaymentAlreadySuccessException 변환")
            void confirmPayment_ShouldMapAlreadyProcessedException() {
                given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossAlreadyProcessedPaymentException());

                assertThatThrownBy(() ->
                        paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT)
                ).isInstanceOf(PaymentAlreadySuccessException.class);
            }

            @Test
            @DisplayName("예외 : 유효하지 않은 카드(TossInvalidCardInfoException) -> InvalidPaymentCardException 변환")
            void confirmPayment_ShouldMapInvalidCardException() {
                given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossInvalidCardInfoException());

                assertThatThrownBy(() ->
                        paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT)
                ).isInstanceOf(InvalidPaymentCardException.class);
            }

            @Test
            @DisplayName("예외 : 잔액 부족(TossNotEnoughBalanceException) -> InsufficientCardBalanceException 변환")
            void confirmPayment_ShouldMapInsufficientBalanceException() {
                given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossNotEnoughBalanceException());

                assertThatThrownBy(() ->
                        paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT)
                ).isInstanceOf(InsufficientCardBalanceException.class);
            }

            @Test
            @DisplayName("예외 : API 키 오류(TossInvalidApiKeyException) -> ExternalApiException(PAYMENT_GATEWAY_CONFIG_ERROR) 변환")
            void confirmPayment_ShouldMapInvalidApiKeyException() {
                given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                        .willThrow(new TossInvalidApiKeyException());

                assertThatThrownBy(() ->
                        paymentGatewayPort.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT)
                ).isInstanceOf(ExternalApiException.class)
                        .hasFieldOrPropertyWithValue("baseCode", PAYMENT_GATEWAY_CONFIG_ERROR);
            }
        }
    }

    @Nested
    @DisplayName("cancelPayment 메서드")
    class CancelPaymentTest {

        private static final String CANCEL_REASON = "Auto-cancel: DB 처리 실패";

        @Nested
        @DisplayName("재시도 및 예외 변환")
        class ExceptionMappingTest {

            @Test
            @DisplayName("예외 : 이미 취소된 결제(TossAlreadyCanceledPaymentException) -> AlreadyCanceledPaymentException 변환")
            void cancelPayment_ShouldMapAlreadyCanceledException() {
                given(tossPaymentsClient.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                        .willThrow(new TossAlreadyCanceledPaymentException());

                assertThatThrownBy(() ->
                        paymentGatewayPort.cancelPayment(PAYMENT_KEY, CANCEL_REASON)
                ).isInstanceOf(AlreadyCanceledPaymentException.class);
            }

            @Test
            @DisplayName("예외 : 취소 불가 상태(TossNotCancelablePaymentException) -> GatewayNotCancelableException 변환")
            void cancelPayment_ShouldMapNotCancelableException() {
                given(tossPaymentsClient.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                        .willThrow(new TossNotCancelablePaymentException());

                assertThatThrownBy(() ->
                        paymentGatewayPort.cancelPayment(PAYMENT_KEY, CANCEL_REASON)
                ).isInstanceOf(GatewayNotCancelableException.class);
            }

            @Test
            @DisplayName("예외 : API 키 오류(TossInvalidApiKeyException) -> ExternalApiException(PAYMENT_GATEWAY_CONFIG_ERROR) 변환")
            void cancelPayment_ShouldMapInvalidApiKeyException() {
                given(tossPaymentsClient.cancelPayment(PAYMENT_KEY, CANCEL_REASON))
                        .willThrow(new TossInvalidApiKeyException());

                assertThatThrownBy(() ->
                        paymentGatewayPort.cancelPayment(PAYMENT_KEY, CANCEL_REASON)
                ).isInstanceOf(ExternalApiException.class)
                        .hasFieldOrPropertyWithValue("baseCode", PAYMENT_GATEWAY_CONFIG_ERROR);
            }
        }
    }

    @Nested
    @DisplayName("inquirePaymentByOrderId 메서드")
    class InquirePaymentByOrderIdTest {

        @Nested
        @DisplayName("예외 변환 및 상태 매핑")
        class MappingTest {

            @Test
            @DisplayName("예외 : 결제 건 없음(TossPaymentNotFoundException) -> PaymentNotFoundException 변환")
            void inquire_ShouldMapNotFoundException() {
                given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID))
                        .willThrow(new TossPaymentNotFoundException());

                assertThatThrownBy(() ->
                        paymentGatewayPort.inquirePaymentByOrderId(ORDER_ID)
                ).isInstanceOf(PaymentNotFoundException.class);
            }

            @Test
            @DisplayName("예외 : API 키 오류(TossInvalidApiKeyException) -> ExternalApiException(PAYMENT_GATEWAY_CONFIG_ERROR) 변환")
            void inquire_ShouldMapInvalidApiKeyException() {
                given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID))
                        .willThrow(new TossInvalidApiKeyException());

                assertThatThrownBy(() ->
                        paymentGatewayPort.inquirePaymentByOrderId(ORDER_ID)
                ).isInstanceOf(ExternalApiException.class)
                        .hasFieldOrPropertyWithValue("baseCode", PAYMENT_GATEWAY_CONFIG_ERROR);
            }

            @Test
            @DisplayName("상태 : DONE -> GatewayPaymentStatus.DONE 매핑 확인")
            void inquire_ShouldMapDoneStatus() {
                TossInquiryResponse response = TossResponseFixture.createInquiryResponse(PAYMENT_KEY, ORDER_ID, "DONE");
                given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID)).willReturn(response);

                PaymentInquiryResult result = paymentGatewayPort.inquirePaymentByOrderId(ORDER_ID);
                assertThat(result.status()).isEqualTo(GatewayPaymentStatus.DONE);
            }

            @Test
            @DisplayName("상태 : CANCELED/ABORTED/EXPIRED -> GatewayPaymentStatus.CANCELED 매핑")
            void inquire_ShouldMapCanceledStatuses() {
                String[] statuses = {"CANCELED", "ABORTED", "EXPIRED"};
                for (String status : statuses) {
                    TossInquiryResponse response = TossResponseFixture.createInquiryResponse(PAYMENT_KEY, ORDER_ID, status);
                    given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID)).willReturn(response);

                    PaymentInquiryResult result = paymentGatewayPort.inquirePaymentByOrderId(ORDER_ID);
                    assertThat(result.status()).isEqualTo(GatewayPaymentStatus.CANCELED);
                }
            }

            @Test
            @DisplayName("상태 : READY/IN_PROGRESS/WAITING_FOR_DEPOSIT -> GatewayPaymentStatus 해당 상태 매핑")
            void inquire_ShouldMapInProgressStatuses() {
                given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID))
                        .willReturn(TossResponseFixture.createInquiryResponse(PAYMENT_KEY, ORDER_ID, "READY"));
                assertThat(paymentGatewayPort.inquirePaymentByOrderId(ORDER_ID).status()).isEqualTo(GatewayPaymentStatus.READY);

                given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID))
                        .willReturn(TossResponseFixture.createInquiryResponse(PAYMENT_KEY, ORDER_ID, "IN_PROGRESS"));
                assertThat(paymentGatewayPort.inquirePaymentByOrderId(ORDER_ID).status()).isEqualTo(GatewayPaymentStatus.IN_PROGRESS);

                given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID))
                        .willReturn(TossResponseFixture.createInquiryResponse(PAYMENT_KEY, ORDER_ID, "WAITING_FOR_DEPOSIT"));
                assertThat(paymentGatewayPort.inquirePaymentByOrderId(ORDER_ID).status()).isEqualTo(GatewayPaymentStatus.IN_PROGRESS);
            }

            @Test
            @DisplayName("상태 : 알 수 없는 상태 -> GatewayPaymentStatus.UNKNOWN 매핑")
            void inquire_ShouldMapUnknownStatus() {
                TossInquiryResponse response = TossResponseFixture.createInquiryResponse(PAYMENT_KEY, ORDER_ID, "SOME_NEW_STATUS");
                given(tossPaymentsClient.inquirePaymentByOrderId(ORDER_ID)).willReturn(response);

                PaymentInquiryResult result = paymentGatewayPort.inquirePaymentByOrderId(ORDER_ID);
                assertThat(result.status()).isEqualTo(GatewayPaymentStatus.UNKNOWN);
            }
        }
    }
}
