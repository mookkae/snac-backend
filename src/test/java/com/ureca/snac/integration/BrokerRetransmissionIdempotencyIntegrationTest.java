package com.ureca.snac.integration;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.TransactionDetail;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.EventFixture;
import com.ureca.snac.wallet.entity.Wallet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    @Test
    @DisplayName("브로커 재전송 시나리오: 동일 메시지 2회 수신 → 포인트 1회만 지급, 자산 내역 1건")
    void shouldGrantBonusOnlyOnce_WhenSameMessageDeliveredTwice() {
        // given
        Member member = createMemberWithWallet("redelivery_");
        Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
        String payload = EventFixture.walletCreatedEventJson(member.getId(), wallet.getId());
        String idempotencyKey = AssetHistory.generateIdempotencyKey(
                TransactionDetail.SIGNUP_BONUS.name(), member.getId());

        // when: 동일 payload 2회 전송 (브로커 재전송 시뮬레이션)
        rabbitTemplate.convertAndSend(RabbitMQQueue.WALLET_CREATED_QUEUE, payload);
        rabbitTemplate.convertAndSend(RabbitMQQueue.WALLET_CREATED_QUEUE, payload);

        // then: 이 멱등키가 생성될 때까지 대기 (메시지 1 처리 완료 기준)
        // 카운터 대신 비즈니스 상태로 동기화 → 다른 테스트 in-flight 메시지의 영향 차단
        await().atMost(15, SECONDS).untilAsserted(() ->
                assertThat(assetHistoryRepository.existsByIdempotencyKey(idempotencyKey)).isTrue()
        );

        // 포인트: 1000 (1회만 입금)
        Wallet updated = walletRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(updated.getPointBalance()).isEqualTo(1000L);

        // 자산 내역: 이 멱등키로 정확히 1건만 존재
        assertThat(assetHistoryRepository.findAll().stream()
                .filter(h -> h.getIdempotencyKey().equals(idempotencyKey))
                .count()).isEqualTo(1);
    }
}
