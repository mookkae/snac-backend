package com.ureca.snac.support;

import com.ureca.snac.auth.service.verify.EmailService;
import com.ureca.snac.auth.service.verify.SnsService;
import com.ureca.snac.common.notification.SlackNotifier;
import com.ureca.snac.outbox.scheduler.DlqMonitor;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * @Retryable AOP 동작만 검증하는 경량 테스트 지원 클래스.
 *
 * <p>Testcontainers 없이 Spring 컨텍스트를 로드한다.
 * <ul>
 *   <li>DB: H2 인메모리 (@TestPropertySource로 오버라이드)</li>
 *   <li>RabbitMQ: ConnectionFactory Mock (@ConditionalOnMissingBean으로 실제 연결 차단)</li>
 *   <li>Redis: RedisConnectionFactory Mock (동일 원리)</li>
 * </ul>
 *
 * <p>모든 협력 객체는 @MockitoBean으로 대체 → 런던파 isolation 유지.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // DB: H2 인메모리 (MySQL 컨테이너 대체)
        "spring.datasource.url=jdbc:h2:mem:retrytest;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // RabbitMQ: ConnectionFactory가 mock이라 실제 연결 안 되지만, placeholder 해소 필요
        "spring.rabbitmq.host=localhost",
        "spring.rabbitmq.port=5672",
        "spring.rabbitmq.username=guest",
        "spring.rabbitmq.password=guest",
        // Actuator Redis health check 비활성화
        "management.health.redis.enabled=false",
        // 리액티브 Redis/Actuator auto-config 제외 — non-reactive mock(RedisConnectionFactory)과 타입 충돌 방지
        "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration," +
                "org.springframework.boot.actuate.autoconfigure.data.redis.RedisReactiveHealthContributorAutoConfiguration"
})
public abstract class RetryTestSupport {

    // RabbitMQ 연결 차단 — @ConditionalOnMissingBean(ConnectionFactory)로 CachingConnectionFactory 생성 억제
    @MockitoBean
    protected ConnectionFactory connectionFactory;

    // Redisson 연결 차단 — RedissonConfig.redissonClient() 대신 mock 사용
    @MockitoBean
    protected RedissonClient redissonClient;

    // Redis 연결 차단 — @ConditionalOnMissingBean(RedisConnectionFactory)로 LettuceConnectionFactory 생성 억제
    @MockitoBean
    protected RedisConnectionFactory redisConnectionFactory;

    // 외부 서비스 Mock
    @MockitoBean
    protected SlackNotifier slackNotifier;

    @MockitoBean
    protected EmailService emailService;

    @MockitoBean
    protected SnsService snsService;

    @MockitoBean
    protected DlqMonitor dlqMonitor;
}
