package com.ureca.snac.integration;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.EventFixture;
import com.ureca.snac.wallet.entity.Wallet;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 브로커 재전송 멱등성 통합 테스트
 * <p>
 * RabbitMQ at-least-once 전달 보장으로 인해 동일 메시지가 여러 번 수신될 수 있음.
 * 멱등키 사전 조회로 포인트 지급은 1회만, 자산 내역도 1건만 기록되는지 검증.
 */
@DisplayName("브로커 재전송 멱등성 통합 테스트")
class BrokerRetransmissionIdempotencyIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    @DisplayName("브로커 재전송 시나리오: 동일 메시지 2회 수신 → 포인트 1회만 지급, 자산 내역 1건")
    void shouldGrantBonusOnlyOnce_WhenSameMessageDeliveredTwice() {
        // given
        Member member = createMemberWithWallet("redelivery_");
        Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
        String payload = EventFixture.walletCreatedEventJson(member.getId(), wallet.getId());

        // 컨텍스트 재사용 대응: 전송 전 카운터 스냅샷
        Counter counter = meterRegistry.counter(
                "listener_message_processed_total",
                "queue", RabbitMQQueue.WALLET_CREATED_QUEUE,
                "result", "success"
        );
        double before = counter.count();

        // when: 동일 payload 2회 전송 (브로커 재전송 시뮬레이션)
        rabbitTemplate.convertAndSend(RabbitMQQueue.WALLET_CREATED_QUEUE, payload);
        rabbitTemplate.convertAndSend(RabbitMQQueue.WALLET_CREATED_QUEUE, payload);

        // then: 두 메시지 모두 처리 완료 대기
        await().atMost(10, SECONDS).untilAsserted(() ->
                assertThat(counter.count()).isEqualTo(before + 2)
        );

        // 포인트: 1000 (1회만 입금)
        Wallet updated = walletRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(updated.getPointBalance()).isEqualTo(1000L);

        // 자산 내역: 1건 (멱등키 unique 제약으로 이중 반영 차단)
        assertThat(assetHistoryRepository.findAll())
                .hasSize(1)
                .first()
                .extracting(AssetHistory::getIdempotencyKey)
                .isEqualTo("SIGNUP_BONUS:" + member.getId());
    }
}
