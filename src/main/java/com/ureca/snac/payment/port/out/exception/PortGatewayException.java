package com.ureca.snac.payment.port.out.exception;

import com.ureca.snac.common.BaseCode;
import com.ureca.snac.common.exception.ExternalApiException;

/**
 * 결제 게이트웨이가 확정 응답을 반환한 포트 계층 예외
 * <p>
 * PG가 요청을 인식하고 확정 결과를 줬으므로 Fail-Safe 처리가 불필요
 * 결과가 불확정인 일시적 장애는 GatewayTransientException 사용
 */
public abstract class PortGatewayException extends ExternalApiException {

    protected PortGatewayException(BaseCode baseCode) {
        super(baseCode);
    }
}
