package com.ureca.snac.integration;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.config.AggregateExchangeMapper;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.member.dto.request.JoinRequest;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.event.MemberJoinEvent;
import com.ureca.snac.member.service.JoinService;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.JoinRequestFixture;
import com.ureca.snac.wallet.entity.Wallet;
import com.ureca.snac.wallet.event.WalletCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 회원가입(Member) -> 지갑생성(Wallet) -> 포인트지급(Point)으로 이어지는
 * 이벤트 기반의 분산 트랜잭션과 데이터 정합성을 검증
 * <p>
 * 1. 회원가입 (트랜잭션 1)
 * Member 저장
 * Outbox(MEMBER_JOIN, INIT) 저장
 * RabbitMQ 발행 (비동기)
 * Outbox -> PUBLISHED
 * <p>
 * 2. 지갑 생성 (트랜잭션 2 - Listener)
 * Wallet 생성 (balance=0, point=0)
 * Outbox(WALLET_CREATED, INIT) 저장
 * RabbitMQ 발행 (비동기)
 * Outbox -> PUBLISHED
 * <p>
 * 3. 포인트 지급 (트랜잭션 3 - Listener)
 * Wallet.point = 1000
 */
@DisplayName("회원가입 이벤트 체이닝 통합 테스트")
class MemberJoinEventChainingIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private JoinService joinService;

    // 상수
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(15);
    private static final long SIGNUP_BONUS_POINT = 1000L;
    private static final int EXPECTED_OUTBOX_COUNT = 2; // MEMBER_JOIN, WALLET_CREATED

    @BeforeEach
    void setUp() {
        // 회원가입 전제 조건 Mock
        given(emailService.isEmailVerified(anyString())).willReturn(true);
        given(snsService.isPhoneVerified(anyString())).willReturn(true);
    }

    @Test
    @DisplayName("시나리오 1 : 회원가입 -> 지갑 생성 -> 회원가입 축하 포인트 지급")
    void scenario1_HappyPath() {
        // given
        JoinRequest request = JoinRequestFixture.create();

        // when : 회원가입
        joinService.joinProcess(request);

        // then 1 : Member 저장 확인
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new AssertionError("Member가 저장되지 않았습니다."));

        assertThat(member.getEmail()).isEqualTo(request.getEmail());
        assertThat(member.getNickname()).isEqualTo(request.getNickname());

        // then 2 : MemberJoinEvent Outbox PUBLISHED 확인
        waitForOutboxStatus(member.getId(), AggregateType.MEMBER, EventType.MEMBER_JOIN, OutboxStatus.PUBLISHED);

        // then 3 : Wallet 생성 대기 및 확인
        Wallet wallet = waitForWallet(member.getId());

        // then 4 : WalletCreatedEvent Outbox PUBLISHED 확인
        waitForOutboxStatus(wallet.getId(), AggregateType.WALLET, EventType.WALLET_CREATED, OutboxStatus.PUBLISHED);

        // then 5 : 포인트 1000 지급 대기 및 확인
        waitForPointDeposit(member.getId(), SIGNUP_BONUS_POINT);

        // then 6 : 최종 잔액 확인
        wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(wallet.getMoneyBalance()).isZero();
        assertThat(wallet.getPointBalance()).isEqualTo(SIGNUP_BONUS_POINT);

        // then 7 : Outbox 개수 및 상태 확인
        List<Outbox> allOutboxes = outboxRepository.findAll();
        assertThat(allOutboxes).hasSize(EXPECTED_OUTBOX_COUNT);
        assertThat(allOutboxes).allMatch(o -> o.getStatus() == OutboxStatus.PUBLISHED);

        // then 8 : RabbitMQ 큐 비었는지 확인
        assertQueueEmpty(RabbitMQQueue.MEMBER_JOINED_QUEUE);
        assertQueueEmpty(RabbitMQQueue.WALLET_CREATED_QUEUE);
    }

    @Test
    @DisplayName("시나리오 2 : RabbitMQ 다운 -> SEND_FAIL 기록 -> 복구 후 스케줄러 재발행")
    void scenario2_RabbitMQDown_AndRecovery() throws Exception {
        // given : RabbitMQ 애플리케이션만 중지 (컨테이너는 생존, 포트 유지)
        // 이렇게 하면 Spring은 연결이 끊겼다고 인식하지만, 재시작 시 포트가 바뀌지 않음
        rabbitMQ.execInContainer("rabbitmqctl", "stop_app");

        JoinRequest request = JoinRequestFixture.create();

        try {
            /*
            1. 장애 상황 SEND_FAIL 기록하기
             */
            // when
            joinService.joinProcess(request);

            // then 1 : Member 저장 확인
            Member member = memberRepository.findByEmail(request.getEmail())
                    .orElseThrow(() -> new AssertionError("Member가 저장되지 않았습니다."));

            // then 2 : Outbox SEND_FAIL 상태 대기
            waitForOutboxStatus(member.getId(), AggregateType.MEMBER, EventType.MEMBER_JOIN, OutboxStatus.SEND_FAIL);

            // then 3 : Outbox 상태 및 retryCount 검증
            List<Outbox> outboxes = findOutboxes(member.getId(), AggregateType.MEMBER, EventType.MEMBER_JOIN);

            assertThat(outboxes)
                    .hasSize(1)
                    .allMatch(o -> o.getRetryCount() >= 1); // 스케줄러가 여러번 했을 수 있어서 최소 1이상이면 통과

            // then 4 : Wallet 생성 안 됨 확인 (이벤트 발행 실패 -> 리스너 동작 X)
            // 이벤트가 발행되지 않았으므로 지갑 생성 리스너는 동작할 수 없음
            assertThat(walletRepository.findByMemberId(member.getId())).isEmpty();

            /*
             2. 시스템 복구 및 스케줄러 재발행
             */
            // when : RabbitMQ 재시작
            rabbitMQ.execInContainer("rabbitmqctl", "start_app");

            // then 5 : 스케줄러가 돌아서 PUBLISHED 로 대기
            waitForOutboxStatus(member.getId(), AggregateType.MEMBER, EventType.MEMBER_JOIN, OutboxStatus.PUBLISHED);

            // then 6 : 지갑 생성 확인
            Wallet wallet = waitForWallet(member.getId());

            // then 7 : 지갑 이벤트 발행 확인 PUBLISHED
            waitForOutboxStatus(wallet.getId(), AggregateType.WALLET, EventType.WALLET_CREATED, OutboxStatus.PUBLISHED);

            // then 8 : 포인트 지급 확인
            waitForPointDeposit(member.getId(), SIGNUP_BONUS_POINT);

        } finally {
            // cleanup 테스트 성공/실패 여부와 상관없이 다음 테스트를 위해 반드시 RabbitMQ 재시작
            rabbitMQ.execInContainer("rabbitmqctl", "start_app");
        }
    }

    @Test
    @DisplayName("시나리오 3 : 지갑 중복 생성 방지 (멱등성)")
    void scenario3_WalletCreation_Idempotency() throws Exception {
        // given
        JoinRequest request = JoinRequestFixture.create();
        joinService.joinProcess(request);

        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow();

        Wallet originalWallet = waitForWallet(member.getId());

        // when : MemberJoinEvent 재발행
        MemberJoinEvent duplicateEvent = new MemberJoinEvent(member.getId());
        republishEvent(duplicateEvent, AggregateType.MEMBER,
                EventType.MEMBER_JOIN, member.getId());

        // then 1 : 큐 비워질 때까지 대기 (리스너 처리 완료)
        waitForQueueEmpty(RabbitMQQueue.MEMBER_JOINED_QUEUE);

        // then 2 : 지갑이 여전히 1개만 존재
        assertThat(walletRepository.findAll()).hasSize(1);

        // then 3 : 기존 지갑 ID , 포인트 1000 유지
        Wallet finalWallet = walletRepository.findByMemberId(member.getId())
                .orElseThrow();
        assertThat(finalWallet.getId()).isEqualTo(originalWallet.getId());
        assertThat(finalWallet.getPointBalance()).isEqualTo(SIGNUP_BONUS_POINT);
    }

    @Test
    @DisplayName("시나리오 4 : 포인트 중복 지급 방지 (멱등성)")
    void scenario4_PointDeposit_Idempotency() throws Exception {
        // given : 회원가입 -> 지갑 생성 -> 포인트 지급 완료
        JoinRequest request = JoinRequestFixture.create();
        joinService.joinProcess(request);

        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow();

        Wallet wallet = waitForWallet(member.getId());
        waitForPointDeposit(member.getId(), SIGNUP_BONUS_POINT);

        // when : WalletCreatedEvent 재발행
        WalletCreatedEvent duplicateEvent = new WalletCreatedEvent(
                wallet.getId(), member.getId());

        republishEvent(duplicateEvent, AggregateType.WALLET,
                EventType.WALLET_CREATED, wallet.getId());

        // then 1 : 큐 비워질 때까지 대기 (리스너 처리 완료)
        waitForQueueEmpty(RabbitMQQueue.WALLET_CREATED_QUEUE);

        // then 2 : 포인트가 여전히 1000 (2000 아님)
        Wallet finalWallet = walletRepository.findByMemberId(member.getId())
                .orElseThrow();
        assertThat(finalWallet.getPointBalance()).isEqualTo(SIGNUP_BONUS_POINT);

        // then 3 : AssetHistory도 1건만 존재 (멱등키로 보장)
        assertThat(assetHistoryRepository.findAll())
                .extracting("idempotencyKey") // 1. 멱등키 속성
                .containsExactly("SIGNUP_BONUS:" + member.getId()); // 2. "이 키 하나만 딱 있는지" 확인
    }

    @Test
    @DisplayName("시나리오 5 : 존재하지 않는 회원 -> DLQ")
    void scenario5_NonExistentMember_SendToDLQ() throws Exception {
        // given : 존재하지 않는 회원
        Long nonExistentMemberId = 999L;

        // when: MemberJoinEvent 발행
        MemberJoinEvent invalidEvent = new MemberJoinEvent(nonExistentMemberId);

        republishEvent(invalidEvent, AggregateType.MEMBER,
                EventType.MEMBER_JOIN, nonExistentMemberId);

        // then 1 : DLQ에 메시지가 들어갈 때까지 대기
        waitForDLQ(RabbitMQQueue.MEMBER_JOINED_DLQ, 1);

        // then 2 : 메인 큐는 비워짐
        assertQueueEmpty(RabbitMQQueue.MEMBER_JOINED_QUEUE);

        // then 3 : 지갑이 생성되지 않음
        assertThat(walletRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("시나리오 6 : JSON 파싱 실패 → DLQ")
    void scenario6_InvalidJson_SendToDLQ() throws Exception {
        // given : 잘못된 JSON 형식의 메시지
        String invalidJson = "{\"invalidField\":\"value\",\"broken:json}";

        // when : 이벤트 발행
        republishEvent(invalidJson, AggregateType.MEMBER,
                EventType.MEMBER_JOIN, 1L);

        // then 1 : DLQ에 메시지가 들어갈 때까지 대기
        waitForDLQ(RabbitMQQueue.MEMBER_JOINED_DLQ, 1);

        // then 2 : 메인 큐는 비워짐
        assertQueueEmpty(RabbitMQQueue.MEMBER_JOINED_QUEUE);

        // then 3 : 지갑 생성되지 않음
        assertThat(walletRepository.findAll()).isEmpty();
    }

    // Helper Method
    // ================= 대기 ====================
    private Wallet waitForWallet(Long memberId) {
        await().atMost(ASYNC_TIMEOUT)
                .until(() -> walletRepository.findByMemberId(memberId).isPresent());

        return walletRepository.findByMemberId(memberId)
                .orElseThrow(() -> new AssertionError("Wallet이 생성되지 않았습니다."));
    }

    private void waitForPointDeposit(Long memberId, long expectedPoint) {
        await().atMost(ASYNC_TIMEOUT)
                .until(() -> {
                    Optional<Wallet> wallet = walletRepository.findByMemberId(memberId);
                    return wallet.isPresent()
                            && wallet.get().getPointBalance() == expectedPoint;
                });
    }

    private void waitForQueueEmpty(String queueName) {
        await().atMost(ASYNC_TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    QueueInformation info = rabbitAdmin.getQueueInfo(queueName);
                    assertThat(info).isNotNull();
                    assertThat(info.getMessageCount()).isZero();
                });
    }

    private void waitForOutboxStatus(Long aggregateId, AggregateType aggregateType,
                                     EventType eventType, OutboxStatus expectedStatus) {
        await().atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    List<Outbox> outboxes = findOutboxes(aggregateId, aggregateType, eventType);
                    assertThat(outboxes)
                            .isNotEmpty()
                            .allMatch(o -> o.getStatus() == expectedStatus);
                });
    }

    private void waitForDLQ(String dlqName, int expectedCount) {
        await().atMost(ASYNC_TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    QueueInformation dlqInfo = rabbitAdmin.getQueueInfo(dlqName);
                    assertThat(dlqInfo).isNotNull();
                    assertThat(dlqInfo.getMessageCount()).isEqualTo(expectedCount);
                });
    }

    // ================= 검증 ====================
    private void assertQueueEmpty(String queueName) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queueName);
        assertThat(info).isNotNull();
        assertThat(info.getMessageCount()).isZero();
    }

    // ================= 조회 ====================
    private List<Outbox> findOutboxes(Long aggregateId, AggregateType aggregateType, EventType eventType) {
        return outboxRepository
                .findByAggregateTypeAndAggregateIdOrderByIdAsc(
                        aggregateType.getTypeName(),
                        aggregateId,
                        PageRequest.of(0, 10)
                )
                .stream()
                .filter(o -> o.getEventType().equals(eventType.getTypeName()))
                .toList();
    }

    // ================= 발행 ====================
    private void republishEvent(Object event, AggregateType aggregateType,
                                EventType eventType, Long aggregateId) throws Exception {
        // 객체가 들어오면 JSON String 변환 이미 String인 경우는 그대로
        Object payload = (event instanceof String) ? event : objectMapper.writeValueAsString(event);

        rabbitTemplate.convertAndSend(
                AggregateExchangeMapper.getExchange(aggregateType),
                eventType.getRoutingKey(),
                payload, // 변환된 String 전송
                message -> {
                    message.getMessageProperties().setHeader("eventType", eventType.getTypeName());
                    message.getMessageProperties().setHeader("aggregateId", aggregateId);
                    return message;
                }
        );
    }
}