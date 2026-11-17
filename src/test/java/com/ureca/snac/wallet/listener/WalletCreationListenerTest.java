package com.ureca.snac.wallet.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.common.metric.TransactionAwareMetricRecorder;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.support.fixture.EventFixture;
import com.ureca.snac.wallet.service.WalletService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * WalletCreationListener 단위 테스트
 * <p>
 * MemberJoinEvent 수신 -> 지갑 생성
 * 재시도 전략 (일시적 장애)
 * 외 DLQ 전략 (JSON 파싱 실패, 회원 없음)
 */
@ExtendWith(MockitoExtension.class)
class WalletCreationListenerTest {

    private WalletCreationListener walletCreationListener;

    @Mock
    private WalletService walletService;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        TransactionAwareMetricRecorder metricRecorder = new TransactionAwareMetricRecorder(meterRegistry);
        ObjectMapper objectMapper = new ObjectMapper();
        walletCreationListener = new WalletCreationListener(
                walletService,
                objectMapper,
                metricRecorder
        );
    }

    @Test
    @DisplayName("정상: MemberJoinEvent 수신 -> 지갑 생성 호출")
    void handleMemberJoinEvent_Success() {
        // given
        Long memberId = 1L;
        String payload = EventFixture.memberJoinEventJson(memberId);

        // when
        walletCreationListener.handleMemberJoinEvent(payload);

        // then
        verify(walletService).createWallet(memberId);
    }

    @Test
    @DisplayName("지표: 지갑을 새로 만들면 success로 센다")
    void handleMemberJoinEvent_Created_CountsSuccess() {
        // given
        Long memberId = 1L;
        when(walletService.createWallet(memberId)).thenReturn(true);

        // when
        walletCreationListener.handleMemberJoinEvent(EventFixture.memberJoinEventJson(memberId));

        // then
        assertThat(processedCount("success")).isEqualTo(1.0);
        assertThat(processedCount("duplicate")).isZero();
    }

    @Test
    @DisplayName("지표: 다시 배달돼 지갑이 이미 있으면 success가 아니라 duplicate로 센다")
    void handleMemberJoinEvent_AlreadyCreated_CountsDuplicate() {
        // given: 브로커 재시작 뒤 이미 처리한 메시지가 다시 배달된 상황
        Long memberId = 1L;
        when(walletService.createWallet(memberId)).thenReturn(false);

        // when
        walletCreationListener.handleMemberJoinEvent(EventFixture.memberJoinEventJson(memberId));

        // then
        assertThat(processedCount("duplicate")).isEqualTo(1.0);
        assertThat(processedCount("success")).isZero();
    }

    private double processedCount(String result) {
        Counter counter = meterRegistry.find("listener_message_processed_total")
                .tags("queue", RabbitMQQueue.MEMBER_JOINED_QUEUE, "result", result)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("JSON 파싱 실패 -> DLQ 이동")
    void handleMemberJoinEvent_JsonParsingFailed() {
        // given: JSON 문제
        String invalidPayload = EventFixture.invalidJson();

        // when , then
        assertThatThrownBy(() -> walletCreationListener.handleMemberJoinEvent(invalidPayload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("JSON 파싱 불가");

        // 서비스 호출 안 됨
        verify(walletService, never()).createWallet(anyLong());
    }

    @Test
    @DisplayName("회원 없음 -> DLQ 이동")
    void handleMemberJoinEvent_MemberNotFound() {
        // given
        Long memberId = 999L;
        String payload = EventFixture.memberJoinEventJson(memberId);

        doThrow(new MemberNotFoundException())
                .when(walletService)
                .createWallet(memberId);

        // when , then
        assertThatThrownBy(() -> walletCreationListener.handleMemberJoinEvent(payload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("회원 없음");
    }

    @Test
    @DisplayName("일시적 장애 -> 재시도")
    void handleMemberJoinEvent_TransientFailure() {
        // given
        Long memberId = 1L;
        String payload = EventFixture.memberJoinEventJson(memberId);

        doThrow(new RuntimeException("DB 연결 실패"))
                .when(walletService)
                .createWallet(memberId);

        // when , then: 예외 그대로 전파 (재시도)
        assertThatThrownBy(() -> walletCreationListener.handleMemberJoinEvent(payload))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB 연결 실패");
    }

    @Test
    @DisplayName("동시성 중복 생성 -> 예외 삼키고 정상 완료 (ACK)")
    void handleMemberJoinEvent_DuplicateWallet_SilentlyHandled() {
        // given
        Long memberId = 1L;
        String payload = EventFixture.memberJoinEventJson(memberId);

        doThrow(new DataIntegrityViolationException("uk_wallet_member_id"))
                .when(walletService)
                .createWallet(memberId);

        // when & then: 예외 전파 없이 정상 완료 (ACK)
        assertThatNoException()
                .isThrownBy(() -> walletCreationListener.handleMemberJoinEvent(payload));
    }
}