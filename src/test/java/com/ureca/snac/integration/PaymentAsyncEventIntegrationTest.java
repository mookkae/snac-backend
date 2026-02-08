package com.ureca.snac.integration;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.TransactionCategory;
import com.ureca.snac.common.event.AggregateType;
import com.ureca.snac.common.event.EventType;
import com.ureca.snac.config.AggregateExchangeMapper;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.fixture.TossResponseFixture;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.money.service.MoneyService;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.outbox.entity.OutboxStatus;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.service.PaymentInternalService;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.EventFixture;
import com.ureca.snac.support.fixture.PaymentCancelResponseFixture;
import com.ureca.snac.wallet.entity.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 결제 비동기 이벤트 통합 테스트
 * RabbitMQ Listener, 보상 트랜잭션, DLQ 라우팅
 */
@DisplayName("결제 비동기 이벤트 통합 테스트")
class PaymentAsyncEventIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MoneyService moneyService;

    @Autowired
    private PaymentInternalService paymentInternalService;

    @MockitoBean
    private PaymentGatewayAdapter paymentGatewayAdapter;

    private Member member;

    private static final Long RECHARGE_AMOUNT = 10000L;
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(15);

    @BeforeEach
    void setUpMember() {
        member = createMemberWithWallet("async_");
    }

    @Nested
    @DisplayName("보상 이벤트")
    class CompensationEventTest {

        @Test
        @DisplayName("성공 : 보상 이벤트 → Wallet 출금 + AssetHistory + compensationCompleted")
        void shouldProcessCompensationEvent() {
            // given: 충전 완료 후 CANCELED 상태로 변경 (토스 취소 성공 시뮬레이션)
            Payment canceledPayment = createCanceledPayment();

            // when: 보상 이벤트 발행
            publishCompensationEvent(canceledPayment);

            // then: 비동기 처리 완료 대기
            waitForCompensationCompleted(canceledPayment.getId());

            // Wallet 출금 확인
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isZero();

            // AssetHistory CANCEL 기록
            List<AssetHistory> cancelHistories = assetHistoryRepository.findAll().stream()
                    .filter(h -> h.getCategory() == TransactionCategory.RECHARGE_CANCEL)
                    .toList();
            assertThat(cancelHistories).hasSize(1);
        }

        @Test
        @DisplayName("멱등성 : 동일 이벤트 2회 발행 → Wallet 출금 1회만")
        void shouldBeIdempotentForDuplicateEvents() {
            // given
            Payment canceledPayment = createCanceledPayment();

            // when: 동일 이벤트 2회 발행
            publishCompensationEvent(canceledPayment);
            publishCompensationEvent(canceledPayment);

            // then: 처리 완료 대기
            waitForCompensationCompleted(canceledPayment.getId());
            waitForQueueEmpty(RabbitMQQueue.PAYMENT_CANCEL_COMPENSATE_QUEUE);

            // Wallet 잔액 0 (1회만 출금)
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isZero();
        }

        @Test
        @DisplayName("실패 : 잘못된 JSON → DLQ 이동")
        void shouldSendInvalidJsonToDLQ() {
            // when: 잘못된 JSON 발행
            publishRawMessage(EventFixture.invalidJson());

            // then: DLQ에 메시지 도착
            waitForDLQ(RabbitMQQueue.PAYMENT_CANCEL_COMPENSATE_DLQ, 1);
            assertQueueEmpty(RabbitMQQueue.PAYMENT_CANCEL_COMPENSATE_QUEUE);
        }

        @Test
        @DisplayName("실패 : Payment 없음 → 재시도 후 DLQ")
        void shouldRetryThenDLQWhenPaymentNotFound() {
            // when: 존재하지 않는 paymentId로 이벤트 발행
            String payload = EventFixture.paymentCancelCompensationEventJson(
                    99999L, member.getId(), RECHARGE_AMOUNT, "없는 결제", OffsetDateTime.now());
            publishRawMessage(payload);

            // then: 재시도 후 DLQ 이동
            waitForDLQ(RabbitMQQueue.PAYMENT_CANCEL_COMPENSATE_DLQ, 1);
            assertQueueEmpty(RabbitMQQueue.PAYMENT_CANCEL_COMPENSATE_QUEUE);
        }
    }

    @Nested
    @DisplayName("E2E 체이닝")
    class EndToEndChainingTest {

        @Test
        @DisplayName("성공 : compensateCancellationFailure → Outbox → RabbitMQ → Listener → 보상 완료")
        void shouldCompleteCompensationChain() {
            // given: 충전 완료
            String paymentKey = "e2e_pk_" + System.currentTimeMillis();
            MoneyRechargePreparedResponse prepared = moneyService.prepareRecharge(
                    new MoneyRechargeRequest(RECHARGE_AMOUNT), member.getEmail());
            mockTossConfirm(paymentKey);
            moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());

            Payment payment = paymentRepository.findByPaymentKeyWithMember(paymentKey).orElseThrow();
            PaymentCancelResponse cancelResponse = PaymentCancelResponseFixture.create(
                    paymentKey, RECHARGE_AMOUNT, "E2E 보상 테스트");

            // when: compensateCancellationFailure 호출 (보상 트랜잭션 시작점)
            paymentInternalService.compensateCancellationFailure(
                    payment, member, cancelResponse, new RuntimeException("DB 실패 시뮬레이션"));

            // then: Outbox PUBLISHED 확인
            waitForOutboxPublished(payment.getId());

            // then: compensationCompleted 확인
            waitForCompensationCompleted(payment.getId());

            // then: Wallet 출금 확인
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isZero();

            // then: AssetHistory CANCEL 기록 확인
            List<AssetHistory> cancelHistories = assetHistoryRepository.findAll().stream()
                    .filter(h -> h.getCategory() == TransactionCategory.RECHARGE_CANCEL)
                    .toList();
            assertThat(cancelHistories).hasSize(1);
        }
    }

    // ================= Helper ====================

    private Payment createCanceledPayment() {
        String paymentKey = "comp_pk_" + System.currentTimeMillis();
        MoneyRechargePreparedResponse prepared = moneyService.prepareRecharge(
                new MoneyRechargeRequest(RECHARGE_AMOUNT), member.getEmail());
        mockTossConfirm(paymentKey);
        moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());

        Payment payment = paymentRepository.findByPaymentKeyWithMember(paymentKey).orElseThrow();
        payment.cancel("보상 테스트용 취소");
        paymentRepository.saveAndFlush(payment);

        return payment;
    }

    private void publishCompensationEvent(Payment payment) {
        String payload = EventFixture.paymentCancelCompensationEventJson(
                payment.getId(), member.getId(), RECHARGE_AMOUNT, "보상 테스트", OffsetDateTime.now());
        publishRawMessage(payload);
    }

    private void publishRawMessage(String payload) {
        rabbitTemplate.convertAndSend(
                AggregateExchangeMapper.getExchange(AggregateType.PAYMENT),
                EventType.PAYMENT_CANCEL_COMPENSATE.getRoutingKey(),
                payload,
                message -> {
                    message.getMessageProperties().setHeader("eventType",
                            EventType.PAYMENT_CANCEL_COMPENSATE.getTypeName());
                    message.getMessageProperties().setHeader("aggregateId", 0L);
                    return message;
                }
        );
    }

    private void waitForCompensationCompleted(Long paymentId) {
        await().atMost(ASYNC_TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    Payment p = paymentRepository.findById(paymentId).orElseThrow();
                    return p.isCompensationCompleted();
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

    private void waitForDLQ(String dlqName, int expectedCount) {
        await().atMost(ASYNC_TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    QueueInformation dlqInfo = rabbitAdmin.getQueueInfo(dlqName);
                    assertThat(dlqInfo).isNotNull();
                    assertThat(dlqInfo.getMessageCount()).isEqualTo(expectedCount);
                });
    }

    private void assertQueueEmpty(String queueName) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queueName);
        assertThat(info).isNotNull();
        assertThat(info.getMessageCount()).isZero();
    }

    private void waitForOutboxPublished(Long paymentId) {
        await().atMost(ASYNC_TIMEOUT)
                .untilAsserted(() -> {
                    List<Outbox> outboxes = outboxRepository
                            .findByAggregateTypeAndAggregateIdOrderByIdAsc(
                                    AggregateType.PAYMENT.getTypeName(),
                                    paymentId,
                                    PageRequest.of(0, 10)
                            ).stream()
                            .filter(o -> o.getEventType().equals(
                                    EventType.PAYMENT_CANCEL_COMPENSATE.getTypeName()))
                            .toList();
                    assertThat(outboxes).isNotEmpty();
                    assertThat(outboxes).allMatch(o -> o.getStatus() == OutboxStatus.PUBLISHED);
                });
    }

    private void mockTossConfirm(String paymentKey) {
        given(paymentGatewayAdapter.confirmPayment(anyString(), anyString(), anyLong()))
                .willReturn(TossResponseFixture.createConfirmResponse(paymentKey));
    }
}
