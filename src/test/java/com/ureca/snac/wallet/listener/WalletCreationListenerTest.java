package com.ureca.snac.wallet.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.common.metric.TransactionAwareMetricRecorder;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.support.fixture.EventFixture;
import com.ureca.snac.wallet.service.WalletService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataIntegrityViolationException;

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

    @BeforeEach
    void setUp() {
        TransactionAwareMetricRecorder metricRecorder = new TransactionAwareMetricRecorder(new SimpleMeterRegistry());
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