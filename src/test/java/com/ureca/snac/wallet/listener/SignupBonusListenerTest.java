package com.ureca.snac.wallet.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.common.metric.TransactionAwareMetricRecorder;
import com.ureca.snac.support.fixture.EventFixture;
import com.ureca.snac.wallet.exception.WalletNotFoundException;
import com.ureca.snac.wallet.service.SignupBonusService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * SignupBonusListener 단위 테스트
 * <p>
 * WalletCreatedEvent 수신 -> 포인트 지급
 * 재시도 전략 (일시적 장애)
 * 외 DLQ 전략 (JSON 파싱 실패, 회원 없음)
 */
@ExtendWith(MockitoExtension.class)
class SignupBonusListenerTest {

    private SignupBonusListener signupBonusListener;

    @Mock
    private SignupBonusService signupBonusService;

    @BeforeEach
    void setUp() {
        TransactionAwareMetricRecorder metricRecorder = new TransactionAwareMetricRecorder(new SimpleMeterRegistry());
        ObjectMapper objectMapper = new ObjectMapper();
        signupBonusListener = new SignupBonusListener(
                signupBonusService,
                objectMapper,
                metricRecorder
        );
    }

    @Test
    @DisplayName("정상: WalletCreatedEvent 수신 -> 포인트 지급")
    void handleWalletCreatedEvent_Success() {
        // given
        Long memberId = 1L;
        Long walletId = 100L;
        String payload = EventFixture.walletCreatedEventJson(memberId, walletId);

        // when
        signupBonusListener.handleWalletCreatedEvent(payload);

        // then
        verify(signupBonusService).grantSignupBonus(memberId);
    }

    @Test
    @DisplayName("JSON 파싱 실패 -> DLQ 이동")
    void handleWalletCreatedEvent_JsonParsingFailed() {
        // given: JSON
        String invalidPayload = EventFixture.invalidJson();

        // when , then
        assertThatThrownBy(() -> signupBonusListener.handleWalletCreatedEvent(invalidPayload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("JSON 파싱 불가");

        // 서비스 호출 안 됨
        verify(signupBonusService, never())
                .grantSignupBonus(anyLong());
    }

    @Test
    @DisplayName("지갑 없음 -> DLQ 이동 (Outbox 커밋 후 수신이므로 데이터 정합성 문제)")
    void handleWalletCreatedEvent_WalletNotFound() {
        // given
        Long memberId = 1L;
        Long walletId = 100L;
        String payload = EventFixture.walletCreatedEventJson(memberId, walletId);

        doThrow(new WalletNotFoundException())
                .when(signupBonusService)
                .grantSignupBonus(memberId);

        // when & then
        assertThatThrownBy(() -> signupBonusListener.handleWalletCreatedEvent(payload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("지갑 없음");
    }

    @Test
    @DisplayName("일시적 장애 -> 재시도")
    void handleWalletCreatedEvent_TransientFailure() {
        // given
        Long memberId = 1L;
        Long walletId = 100L;
        String payload = EventFixture.walletCreatedEventJson(memberId, walletId);

        // 일시적 장애 (ex DB 커넥션 끊김)
        doThrow(new RuntimeException("DB 연결 실패"))
                .when(signupBonusService)
                .grantSignupBonus(memberId);

        // when , then: 예외 그대로 전파 (재시도)
        assertThatThrownBy(() -> signupBonusListener.handleWalletCreatedEvent(payload))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB 연결 실패");
    }

    @Test
    @DisplayName("동시성 중복 지급 -> 예외 삼키고 정상 완료 (ACK)")
    void handleWalletCreatedEvent_DuplicateBonus_SilentlyHandled() {
        // given
        Long memberId = 1L;
        Long walletId = 100L;
        String payload = EventFixture.walletCreatedEventJson(memberId, walletId);

        doThrow(new DataIntegrityViolationException("uk_asset_history_idempotency_key"))
                .when(signupBonusService)
                .grantSignupBonus(memberId);

        // when & then: 예외 전파 없이 정상 완료 (ACK)
        assertThatNoException()
                .isThrownBy(() -> signupBonusListener.handleWalletCreatedEvent(payload));
    }
}