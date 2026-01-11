package com.ureca.snac.wallet.service;

import com.ureca.snac.asset.entity.TransactionDetail;
import com.ureca.snac.asset.repository.AssetHistoryRepository;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.wallet.exception.WalletNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * SignupBonusService 단위 테스트
 * <p>
 * 최초 회원가입 보너스
 * 중복 지급 방지 (멱등성 보장)
 * 회원이 없거나 지갑이 없거나 하는 예외 상황
 */
@ExtendWith(MockitoExtension.class)
class SignupBonusServiceTest {

    @InjectMocks
    private SignupBonusServiceImpl signupBonusService;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private WalletService walletService;

    @Mock
    private AssetHistoryRepository assetHistoryRepository;

    @Test
    @DisplayName("정상 : 첫 포인트 지급 성공")
    void grantSignupBonus_Success() {
        // given
        Long memberId = 1L;
        Member member = MemberFixture.createMember(memberId);

        given(assetHistoryRepository.existsByMemberIdAndTransactionDetail(
                memberId, TransactionDetail.SIGNUP_BONUS))
                .willReturn(false);  // 지급 이력 없음

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(member));

        given(walletService.depositPoint(memberId, 1000L))
                .willReturn(1000L);  // 잔액: 1000

        // when
        signupBonusService.grantSignupBonus(memberId);

        // then
        verify(assetHistoryRepository, times(1))
                .existsByMemberIdAndTransactionDetail(memberId, TransactionDetail.SIGNUP_BONUS);

        verify(walletService, times(1))
                .depositPoint(memberId, 1000L);

        verify(assetHistoryRepository, times(1))
                .save(any());
    }

    @Test
    @DisplayName("멱등성 : 이미 포인트 지급된 경우 중복 지급 안 함")
    void grantSignupBonus_Idempotent() {
        // given
        Long memberId = 1L;

        given(assetHistoryRepository.existsByMemberIdAndTransactionDetail(
                memberId, TransactionDetail.SIGNUP_BONUS
        )).willReturn(true);  // 이미 지급됨

        // when
        signupBonusService.grantSignupBonus(memberId);

        // then : 멱등성 체크만 하고 종료
        verify(assetHistoryRepository, times(1))
                .existsByMemberIdAndTransactionDetail(memberId, TransactionDetail.SIGNUP_BONUS);

        // 회원 조회도 안 함
        verify(memberRepository, never())
                .findById(anyLong());

        // 포인트 지급도 안 함
        verify(walletService, never())
                .depositPoint(anyLong(), anyLong());

        // 내역 저장도 안 함
        verify(assetHistoryRepository, never())
                .save(any());
    }

    @Test
    @DisplayName("예외 : 회원 없음")
    void grantSignupBonus_MemberNotFound() {
        // given
        Long memberId = 999L;

        given(assetHistoryRepository.existsByMemberIdAndTransactionDetail(
                memberId, TransactionDetail.SIGNUP_BONUS
        )).willReturn(false);

        given(memberRepository.findById(memberId))
                .willReturn(Optional.empty());

        // when , then
        assertThatThrownBy(() -> signupBonusService.grantSignupBonus(memberId))
                .isInstanceOf(MemberNotFoundException.class);

        verify(walletService, never())
                .depositPoint(anyLong(), anyLong());

        verify(assetHistoryRepository, never())
                .save(any());
    }

    @Test
    @DisplayName("예외 : 지갑 없음")
    void grantSignupBonus_WalletNotFound() {
        // given
        Long memberId = 1L;
        Member member = MemberFixture.createMember(memberId);

        given(assetHistoryRepository.existsByMemberIdAndTransactionDetail(
                memberId, TransactionDetail.SIGNUP_BONUS
        )).willReturn(false);

        given(memberRepository.findById(memberId))
                .willReturn(Optional.of(member));

        // WalletService가 WalletNotFoundException 던짐
        given(walletService.depositPoint(memberId, 1000L))
                .willThrow(new WalletNotFoundException());

        // when , then
        assertThatThrownBy(() -> signupBonusService.grantSignupBonus(memberId))
                .isInstanceOf(WalletNotFoundException.class);

        verify(assetHistoryRepository, never()).save(any());
    }
}