package com.ureca.snac.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.TransientDataAccessResourceException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리스너 재시도 정책 단위 테스트
 * <p>
 * 공용 리스너 팩토리의 재시도 인터셉터를 스프링 없이 직접 감싸서 시도 횟수를 센다.
 * 통합 테스트로 쓰면 캐시된 다른 컨텍스트의 리스너가 메시지를 가져가서 결과가 흔들린다
 */
class ListenerRetryPolicyTest {

    private static final int MAX_ATTEMPTS = 3;

    private final AtomicInteger attempts = new AtomicInteger();
    private final Message message = new Message(new byte[0], new MessageProperties());

    private RuntimeException failure;
    private int succeedOnAttempt;
    private Handler handler;

    interface Handler {
        void handle(Object channel, Message message);
    }

    @BeforeEach
    void setUp() {
        Handler target = (channel, received) -> {
            if (attempts.incrementAndGet() != succeedOnAttempt) {
                throw failure;
            }
        };

        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new OutboxRabbitMQConfig(0).retryInterceptor(MAX_ATTEMPTS, 1L, 1.0, 1L));
        handler = (Handler) proxyFactory.getProxy();
    }

    @Test
    @DisplayName("잠깐의 장애는 다시 시도해서 성공한다")
    void transientFailureIsRetried() {
        // given : 세 번째 시도에 성공
        failure = new TransientDataAccessResourceException("일시 장애");
        succeedOnAttempt = 3;

        // when
        handler.handle(null, message);

        // then
        assertThat(attempts).hasValue(3);
    }

    @Test
    @DisplayName("다시 시도해도 안 되면 DLQ로 보내는 예외로 끝난다")
    void exhaustedRetryRejectsMessage() {
        // given
        failure = new TransientDataAccessResourceException("계속 장애");

        // when & then
        assertThatThrownBy(() -> handler.handle(null, message))
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(attempts).hasValue(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("확정 실패는 다시 시도하지 않는다")
    void rejectedFailureIsNotRetried() {
        // given
        failure = new AmqpRejectAndDontRequeueException("JSON 파싱 불가");

        // when & then
        assertThatThrownBy(() -> handler.handle(null, message))
                .hasCauseInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThat(attempts).hasValue(1);
    }
}
