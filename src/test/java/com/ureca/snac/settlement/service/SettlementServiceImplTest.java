package com.ureca.snac.settlement.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.settlement.application.service.SettlementAlertService;
import com.ureca.snac.settlement.application.service.SettlementService;
import com.ureca.snac.settlement.domain.repository.SettlementRepository;
import com.ureca.snac.settlement.domain.service.SettlementValidator;
import com.ureca.snac.support.RetryTestSupport;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * SettlementServiceImpl 단위 테스트 (Spring Support)
 *
 * @Retryable AOP 동작 검증을 위해 Spring Context를 로드하지만,
 * 모든 협력 객체는 Mock으로 대체하여 단위 기능을 검증함.
 */
@DisplayName("SettlementServiceImpl 단위 테스트")
class SettlementServiceImplTest extends RetryTestSupport {

    @Autowired
    private SettlementService settlementService;

    @MockitoBean
    private SettlementRepository settlementRepository;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private WalletService walletService;

    @MockitoBean
    private SettlementValidator settlementValidator;

    @MockitoBean
    private AssetRecorder assetRecorder;

    @MockitoBean
    private SettlementAlertService settlementAlertService;

    // SlackNotifier는 RetryTestSupport에서 @MockitoBean으로 등록됨 (상속)

    private Member member;

    private static final String USERNAME = "member@test.com";
    private static final long AMOUNT = 50000L;
    private static final String ACCOUNT_NUMBER = "1234567890";

    @BeforeEach
    void setUp() {
        member = MemberFixture.createMember(1L);
    }

    @Nested
    @DisplayName("processSettlement 메서드")
    class ProcessSettlementTest {

        @Nested
        @DisplayName("재시도 동작")
        class RetryBehaviorTest {

            @Test
            @DisplayName("정상 : TransientDataAccessException 발생 시 최대 3회 재시도")
            void processSettlement_shouldRetryOnTransientDataAccessException() {
                // given
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(member));
                given(walletService.withdrawMoney(anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {});

                // when & then
                assertThatThrownBy(() ->
                        settlementService.processSettlement(USERNAME, AMOUNT, ACCOUNT_NUMBER)
                ).isInstanceOf(TransientDataAccessException.class);

                verify(walletService, times(3)).withdrawMoney(anyLong(), anyLong());
            }

            @Test
            @DisplayName("정상 : 2회 실패 후 3회차에 성공")
            void processSettlement_shouldSucceedOnThirdAttempt() {
                // given
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(member));
                given(walletService.withdrawMoney(anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {})
                        .willThrow(new TransientDataAccessException("DB timeout") {})
                        .willReturn(0L);

                // when
                settlementService.processSettlement(USERNAME, AMOUNT, ACCOUNT_NUMBER);

                // then
                verify(walletService, times(3)).withdrawMoney(anyLong(), anyLong());
            }

            @Test
            @DisplayName("정상 : @Recover 호출 시 SettlementAlertService.alertSettlementFailure() 발동")
            void processSettlement_shouldSendAlertOnRecovery() {
                // given - 3회 모두 실패
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(member));
                given(walletService.withdrawMoney(anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {});

                // when & then
                assertThatThrownBy(() ->
                        settlementService.processSettlement(USERNAME, AMOUNT, ACCOUNT_NUMBER)
                ).isInstanceOf(RuntimeException.class);

                verify(settlementAlertService, times(1)).alertSettlementFailure(anyString(), anyLong(), any());
            }
        }
    }
}
