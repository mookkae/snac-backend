package com.ureca.snac.wallet.service;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.wallet.event.WalletCreatedEvent;
import com.ureca.snac.wallet.repository.WalletRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * WalletService 단위 테스트
 * <p>
 * 지갑 중복 생성 방지 (멱등성)
 * 회원 없음 예외 처리
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @InjectMocks
    private WalletServiceImpl walletService;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Test
    @DisplayName("멱등성 : 이미 지갑이 있으면 생성 안 함")
    void createWallet_Idempotent() {
        // given
        Member member = MemberFixture.createMember(1L);

        given(walletRepository.existsByMemberId(member.getId()))
                .willReturn(true);

        // when
        walletService.createWallet(member);

        // then
        verify(walletRepository, times(1))
                .existsByMemberId(member.getId());

        verify(walletRepository, never())
                .save(any());

        verify(eventPublisher, never())
                .publishEvent(any(WalletCreatedEvent.class));
    }

    @Test
    @DisplayName("정상 : 지갑 생성 및 WalletCreatedEvent 발행")
    void createWallet_Success() {
        // given
        Member member = MemberFixture.createMember(1L);

        given(walletRepository.existsByMemberId(member.getId()))
                .willReturn(false);

        // when
        walletService.createWallet(member);

        // then
        verify(walletRepository, times(1))
                .existsByMemberId(member.getId());

        verify(walletRepository, times(1))
                .save(any());

        verify(eventPublisher, times(1))
                .publishEvent(any(WalletCreatedEvent.class));
    }
}