package com.ureca.snac.integration;

import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.member.dto.request.JoinRequest;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.service.JoinService;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.JoinRequestFixture;
import com.ureca.snac.wallet.entity.Wallet;
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

        // when: 회원가입
        joinService.joinProcess(request);

        // then 1 : Member 저장 확인
        Member member = memberRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new AssertionError("Member가 저장되지 않았습니다."));

        assertThat(member.getEmail()).isEqualTo(request.getEmail());
        assertThat(member.getNickname()).isEqualTo(request.getNickname());

        // then 2 : MemberJoinEvent Outbox PUBLISHED 확인
        waitForOutboxStatus(member.getId(), AggregateType.MEMBER, EventType.MEMBER_JOIN, OutboxStatus.PUBLISHED);

        // then 3 : Wallet 생성 대기 및 확인
        waitForWallet(member.getId());

        Wallet wallet = walletRepository.findByMemberId(member.getId())
                .orElseThrow(() -> new AssertionError("Wallet이 생성되지 않았습니다."));
        assertThat(wallet.getMember().getId()).isEqualTo(member.getId());

        // then 4 : WalletCreatedEvent Outbox PUBLISHED 확인
        waitForOutboxStatus(wallet.getId(), AggregateType.WALLET, EventType.WALLET_CREATED, OutboxStatus.PUBLISHED);

        // then 5 : 포인트 1000 지급 대기 및 확인
        waitForPointDeposit(member.getId(), SIGNUP_BONUS_POINT);

        // then 6 : 최종 잔액 확인
        Wallet finalWallet = walletRepository.findByMemberId(member.getId())
                .orElseThrow(() -> new AssertionError("Wallet 조회 실패"));
        assertThat(finalWallet.getMoneyBalance()).isZero();
        assertThat(finalWallet.getPointBalance()).isEqualTo(SIGNUP_BONUS_POINT);

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
            // when : 회원가입 (RabbitMQ 네트워크 실패로 발행 실패 예상)
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
                    .allMatch(o -> o.getRetryCount() == 1); // 첫 시도 실패이므로 카운트 1

            // then 4 : Wallet 생성 안 됨 확인 (이벤트 발행 실패 -> 리스너 동작 X)
            // 이벤트가 발행되지 않았으므로 지갑 생성 리스너는 동작할 수 없음
            Optional<Wallet> walletBeforeRecovery = walletRepository.findByMemberId(member.getId());
            assertThat(walletBeforeRecovery).isEmpty();

            /*
             2. 시스템 복구 및 스케줄러 재발행
             */
            // when : RabbitMQ 재시작
            rabbitMQ.execInContainer("rabbitmqctl", "start_app");

            // then 5 : 스케줄러가 돌아서 PUBLISHED 로 대기
            waitForOutboxStatus(member.getId(), AggregateType.MEMBER, EventType.MEMBER_JOIN, OutboxStatus.PUBLISHED);

            // then 6 : 지갑 생성 확인
            waitForWallet(member.getId());
            Wallet wallet = walletRepository.findByMemberId(member.getId())
                    .orElseThrow(() -> new AssertionError("Wallet이 생성되지 않았습니다."));

            // then 7 : 지갑 이벤트 발행 확인 PUBLISHED
            waitForOutboxStatus(wallet.getId(), AggregateType.WALLET, EventType.WALLET_CREATED, OutboxStatus.PUBLISHED);

            // then 8 : 포인트 지급 확인
            waitForPointDeposit(member.getId(), SIGNUP_BONUS_POINT);

            // then 9 : 최종
            Wallet finalWallet = walletRepository.findByMemberId(member.getId())
                    .orElseThrow();
            assertThat(finalWallet.getPointBalance()).isEqualTo(SIGNUP_BONUS_POINT);

            // then 10 : Outbox 2개다 PUBLISHED
            List<Outbox> allOutboxes = outboxRepository.findAll();
            assertThat(allOutboxes).hasSize(EXPECTED_OUTBOX_COUNT);
            assertThat(allOutboxes).allMatch(o -> o.getStatus() == OutboxStatus.PUBLISHED);

        } finally {
            // cleanup 테스트 성공/실패 여부와 상관없이 다음 테스트를 위해 반드시 RabbitMQ 재시작
            rabbitMQ.execInContainer("rabbitmqctl", "start_app");
        }
    }

    // Helper Method
    // Wallet 생성 대기
    private void waitForWallet(Long memberId) {
        await().atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    Optional<Wallet> wallet = walletRepository.findByMemberId(memberId);
                    assertThat(wallet).isPresent();
                });
    }

    // 포인트 지급 대기
    private void waitForPointDeposit(Long memberId, long expectedPoint) {
        await().atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    Wallet wallet = walletRepository.findByMemberId(memberId)
                            .orElseThrow(() -> new AssertionError("Wallet이 존재하지 않습니다."));
                    assertThat(wallet.getPointBalance()).isEqualTo(expectedPoint);
                });
    }

    // Aggregate ID와 EventType으로 Outbox 조회
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

    // Outbox 특정 상태 대기
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

    // RabbitMQ 큐가 비어있는지 확인
    private void assertQueueEmpty(String queueName) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queueName);
        assertThat(info).isNotNull();
        assertThat(info.getMessageCount()).isZero();
    }
}