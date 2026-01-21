package com.ureca.snac.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.asset.repository.AssetHistoryRepository;
import com.ureca.snac.auth.service.verify.EmailService;
import com.ureca.snac.auth.service.verify.SnsService;
import com.ureca.snac.common.notification.SlackNotifier;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.member.repository.SocialLinkRepository;
import com.ureca.snac.outbox.repository.OutboxRepository;
import com.ureca.snac.outbox.scheduler.DlqMonitor;
import com.ureca.snac.wallet.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합 테스트 추상 부모 클래스
 * <p>
 * Testcontainers 싱글톤 관리 (MySQL, RabbitMQ, Redis)
 * 외부 서비스 Mock (Email, SNS, Slack, DLQ Monitor)
 * <p>
 * static 블록으로 컨테이너 수동 제어 및 withReuse 재사용으로 성능 최적화
 *
 * @BeforeEach 로 cleanup()보장
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

    // Testcontainers 싱글톤
    protected static MySQLContainer mysql;
    protected static RabbitMQContainer rabbitMQ;
    protected static GenericContainer<?> redis;

    static {
        // 도커 이미지 불러오고
        // 1. MySQL
        mysql = new MySQLContainer(DockerImageName.parse("mysql:8.0"))
                .withDatabaseName("testdb")
                .withUsername("test")
                .withPassword("test")
                .withReuse(true);
        mysql.start();

        // 2. RabbitMQ
        rabbitMQ = new RabbitMQContainer(DockerImageName.parse("rabbitmq:management"))
                .withReuse(true);
        rabbitMQ.start();

        // 3. Redis
        redis = new GenericContainer<>(DockerImageName.parse("redis:8.4.0"))
                .withExposedPorts(6379)
                .withReuse(true);

        redis.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // MySQL 설정
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        // RabbitMQ 설정
        registry.add("spring.rabbitmq.host", rabbitMQ::getHost);
        registry.add("spring.rabbitmq.port", rabbitMQ::getAmqpPort);

        // Redis 설정
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }


    // 외부 서비스 Mock
    @MockitoBean
    protected EmailService emailService;

    @MockitoBean
    protected SnsService snsService;

    @MockitoBean
    protected SlackNotifier slackNotifier;

    @MockitoBean
    protected DlqMonitor dlqMonitor;

    // 필요한 Repository, 빈
    @Autowired
    protected MemberRepository memberRepository;

    @Autowired
    protected OutboxRepository outboxRepository;

    @Autowired
    protected WalletRepository walletRepository;

    @Autowired
    protected AssetHistoryRepository assetHistoryRepository;

    @Autowired
    protected SocialLinkRepository socialLinkRepository;

    @Autowired
    protected AmqpAdmin rabbitAdmin;

    @Autowired
    protected RedisTemplate<String, String> redisTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected RabbitTemplate rabbitTemplate;

    // FK 순서 철저히 지켜서 각 테스트 전 데이터 정리, BeforeEach
    @BeforeEach
    void cleanup() {
        // 1. RabbitMQ 큐 비우기
        purgeAllQueues();
        // 메시지 큐 -> 캐시 -> 영구 저장소 (빠른 거에서 느린 것)

        redisTemplate.execute((RedisConnection connection) -> {
            connection.serverCommands().flushDb();
            return null;
        });
        // 2. FK 순서 (자식 -> 부모)
        socialLinkRepository.deleteAllInBatch();

        assetHistoryRepository.deleteAllInBatch();

        walletRepository.deleteAllInBatch();
        outboxRepository.deleteAllInBatch();

        memberRepository.deleteAllInBatch();
    }

    private void purgeAllQueues() {
        rabbitAdmin.purgeQueue(RabbitMQQueue.MEMBER_JOINED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQQueue.MEMBER_JOINED_DLQ, false);

        rabbitAdmin.purgeQueue(RabbitMQQueue.WALLET_CREATED_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitMQQueue.WALLET_CREATED_DLQ, false);
    }
}