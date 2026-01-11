package com.ureca.snac.wallet.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.support.fixture.EventFixture;
import com.ureca.snac.wallet.service.SignupBonusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

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
        ObjectMapper objectMapper = new ObjectMapper();
        signupBonusListener = new SignupBonusListener(
                signupBonusService,
                objectMapper
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
        verify(signupBonusService, times(1))
                .grantSignupBonus(memberId);
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
    @DisplayName("회원 없음 -> DLQ 이동")
    void handleWalletCreatedEvent_MemberNotFound() {
        // given
        Long memberId = 999L;
        Long walletId = 100L;
        String payload = EventFixture.walletCreatedEventJson(memberId, walletId);

        // SignupBonusService가 MemberNotFoundException 던짐
        doThrow(new MemberNotFoundException())
                .when(signupBonusService)
                .grantSignupBonus(memberId);

        // when , then
        assertThatThrownBy(() -> signupBonusListener.handleWalletCreatedEvent(payload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("회원 없음");
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
}