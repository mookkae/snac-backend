package com.ureca.snac.wallet.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.wallet.exception.WalletNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 최초 회원가입 보너스
 * 중복 지급 방지 (멱등성 보장)
 * 회원이 없거나 지갑이 없거나 하는 예외 상황
 */
@DisplayName("SignupBonusService 단위 테스트")
@ExtendWith(MockitoExtension.class)
class SignupBonusServiceTest {

    private SignupBonusServiceImpl signupBonusService;

    @Mock
    private WalletService walletService;

    @Mock
    private AssetRecorder assetRecorder;

    @BeforeEach
    void setUp() {
        signupBonusService = new SignupBonusServiceImpl(walletService, assetRecorder);
    }

    private static final Long MEMBER_ID = 1L;
    private static final Long BONUS_AMOUNT = 1000L;

    @Nested
    @DisplayName("grantSignupBonus 메서드")
    class GrantSignupBonusTest {

        @Test
        @DisplayName("성공 : 첫 포인트 지급 성공")
        void grantSignupBonus_success() {
            // given
            given(assetRecorder.hasSignupBonusRecord(MEMBER_ID)).willReturn(false);
            given(walletService.depositPoint(MEMBER_ID, BONUS_AMOUNT)).willReturn(BONUS_AMOUNT);

            // when
            signupBonusService.grantSignupBonus(MEMBER_ID);

            // then
            verify(walletService).depositPoint(MEMBER_ID, BONUS_AMOUNT);
            verify(assetRecorder).recordSignupBonus(MEMBER_ID, BONUS_AMOUNT, BONUS_AMOUNT);
        }

        @Test
        @DisplayName("멱등성 : 이미 포인트 지급된 경우 중복 지급 안 함")
        void grantSignupBonus_idempotent() {
            // given
            given(assetRecorder.hasSignupBonusRecord(MEMBER_ID)).willReturn(true);

            // when
            signupBonusService.grantSignupBonus(MEMBER_ID);

            // then
            verify(walletService, never()).depositPoint(anyLong(), anyLong());
            verify(assetRecorder, never()).recordSignupBonus(anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("실패 : 지갑 없음")
        void grantSignupBonus_walletNotFound() {
            // given
            given(assetRecorder.hasSignupBonusRecord(MEMBER_ID)).willReturn(false);
            given(walletService.depositPoint(MEMBER_ID, BONUS_AMOUNT)).willThrow(new WalletNotFoundException());

            // when, then
            assertThatThrownBy(() -> signupBonusService.grantSignupBonus(MEMBER_ID))
                    .isInstanceOf(WalletNotFoundException.class);

            verify(assetRecorder, never()).recordSignupBonus(anyLong(), anyLong(), anyLong());
        }
    }
}
