package com.ureca.snac.payment.port.out;

import com.ureca.snac.payment.port.out.dto.PaymentCancelResult;
import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.payment.port.out.dto.PaymentInquiryResult;

/**
 * 외부 결제 게이트웨이 포트
 * infra DTO·예외가 이 경계 밖으로 노출되지 않도록 어댑터에서 도메인 타입으로 변환한다.
 */
public interface PaymentGatewayPort {

    /**
     * 결제 승인을 요청한다.
     *
     * @param paymentKey 결제 건 식별키
     * @param orderId    우리 시스템의 고유 주문번호
     * @param amount     승인할 금액
     * @return 도메인 표준 승인 결과
     */
    PaymentConfirmResult confirmPayment(String paymentKey, String orderId, Long amount);

    /**
     * 결제 취소를 요청한다.
     *
     * @param paymentKey 결제건 식별키
     * @param reason     취소 사유
     * @return 우리 시스템의 표준 결제 취소응답 DTO
     */
    PaymentCancelResult cancelPayment(String paymentKey, String reason);

    /**
     * 주문번호로 결제 상태를 조회한다.
     *
     * @param orderId 우리 시스템의 고유 주문번호
     * @return 도메인 표준 조회 결과
     */
    PaymentInquiryResult inquirePaymentByOrderId(String orderId);
}
