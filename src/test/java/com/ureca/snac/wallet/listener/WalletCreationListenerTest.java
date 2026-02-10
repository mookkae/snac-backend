package com.ureca.snac.wallet.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.support.fixture.EventFixture;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.wallet.service.WalletService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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
    private SimpleMeterRegistry meterRegistry;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private WalletService walletService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        ObjectMapper objectMapper = new ObjectMapper();
        walletCreationListener = new WalletCreationListener(
                memberRepository,
                walletService,
                objectMapper,
                meterRegistry
        );
    }

    @Test
    @DisplayName("정상: MemberJoinEvent 수신 -> 지갑 생성 호출")
    void handleMemberJoinEvent_Success() {
        // given
        Long memberId = 1L;
        Member member = MemberFixture.createMember(memberId);
        String payload = EventFixture.memberJoinEventJson(memberId);

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(member));

        // when
        walletCreationListener.handleMemberJoinEvent(payload);

        // then
        verify(memberRepository, times(1))
                .findById(memberId);

        verify(walletService, times(1))
                .createWallet(member);

        // 메트릭 검증
        assertThat(meterRegistry.get("listener_message_processed_total")
                .tag("result", "success").counter().count()).isEqualTo(1.0);
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
        verify(memberRepository, never())
                .findById(anyLong());

        verify(walletService, never())
                .createWallet(any(Member.class));
    }

    @Test
    @DisplayName("회원 없음 -> DLQ 이동")
    void handleMemberJoinEvent_MemberNotFound() {
        // given
        Long memberId = 999L;
        String payload = EventFixture.memberJoinEventJson(memberId);

        given(memberRepository.findById(memberId))
                .willReturn(Optional.empty());

        // when , then
        assertThatThrownBy(() -> walletCreationListener.handleMemberJoinEvent(payload))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("회원 없음");

        verify(walletService, never())
                .createWallet(any(Member.class));
    }

    @Test
    @DisplayName("일시적 장애 -> 재시도")
    void handleMemberJoinEvent_TransientFailure() {
        // given
        Long memberId = 1L;
        Member member = MemberFixture.createMember(memberId);
        String payload = EventFixture.memberJoinEventJson(memberId);

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(member));

        // 일시적 장애 (ex DB 커넥션 끊김)
        doThrow(new RuntimeException("DB 연결 실패"))
                .when(walletService)
                .createWallet(member);

        // when , then: 예외 그대로 전파 (재시도)
        assertThatThrownBy(() -> walletCreationListener.handleMemberJoinEvent(payload))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("DB 연결 실패");
    }
}