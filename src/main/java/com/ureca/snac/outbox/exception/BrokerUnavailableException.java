package com.ureca.snac.outbox.exception;

import org.springframework.amqp.AmqpException;

/**
 * 메시지가 아니라 브로커 연결이 원인인 발행 실패
 * <p>
 * 호출자는 이 예외면 재시도 횟수를 쓰지 않고 다음 주기에 다시 시도한다.
 */
public class BrokerUnavailableException extends AmqpException {

    public BrokerUnavailableException(Throwable cause) {
        super("브로커 연결 장애", cause);
    }
}
