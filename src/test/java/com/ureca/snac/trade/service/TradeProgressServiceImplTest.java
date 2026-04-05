package com.ureca.snac.trade.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.board.entity.Card;
import com.ureca.snac.board.repository.CardRepository;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.member.service.MemberService;
import com.ureca.snac.support.RetryTestSupport;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.trade.fixture.CardFixture;
import com.ureca.snac.trade.fixture.TradeFixture;
import com.ureca.snac.trade.repository.TradeRepository;
import com.ureca.snac.trade.service.interfaces.TradeProgressService;
import com.ureca.snac.wallet.dto.CompositeBalanceResult;
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
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.*;

/**
 * TradeProgressServiceImpl 단위 테스트 (Spring Support)
 *
 * @Retryable AOP 동작 검증을 위해 Spring Context를 로드하지만,
 * 모든 협력 객체는 Mock으로 대체하여 단위 기능을 검증함.
 */
@DisplayName("TradeProgressServiceImpl 단위 테스트")
class TradeProgressServiceImplTest extends RetryTestSupport {

    @Autowired
    private TradeProgressService tradeProgressService;

    @MockitoBean
    private TradeRepository tradeRepository;

    @MockitoBean
    private CardRepository cardRepository;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private WalletService walletService;

    @MockitoBean
    private AssetRecorder assetRecorder;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private TradeAlertService tradeAlertService;

    // SlackNotifier는 RetryTestSupport에서 @MockitoBean으로 등록됨 (상속)

    private Member buyer;
    private Member seller;
    private Card soldOutCard;

    private static final Long TRADE_ID = 1L;
    private static final Long CARD_ID = 1L;
    private static final int PRICE = 10000;

    @BeforeEach
    void setUp() {
        buyer = MemberFixture.createMember(1L);
        seller = MemberFixture.createMember(2L);
        soldOutCard = CardFixture.createTradingCard(CARD_ID, seller, PRICE);
    }

    @Nested
    @DisplayName("confirmTrade 메서드")
    class ConfirmTradeTest {

        @Nested
        @DisplayName("에스크로 차감 동작")
        class EscrowDeductTest {

            @Test
            @DisplayName("정상 : 머니만 사용한 거래 확정 시 deductCompositeEscrow(buyer, money, 0) 호출")
            void confirmTrade_moneyOnly_shouldDeductBuyerEscrow() {
                // given — priceGb=10000, point=0 → moneyToDeduct=10000, pointToDeduct=0
                willAnswer(inv -> Optional.of(
                        TradeFixture.createDataSentTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE)
                )).given(tradeRepository).findLockedById(anyLong());
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
                given(walletService.deductCompositeEscrow(anyLong(), anyLong(), anyLong()))
                        .willReturn(new CompositeBalanceResult(0L, 0L, 0L, 0L));
                given(walletService.depositMoney(anyLong(), anyLong())).willReturn(20000L);
                given(walletService.depositPoint(anyLong(), anyLong())).willReturn(500L);
                willAnswer(inv -> Optional.of(
                        CardFixture.createTradingCard(CARD_ID, seller, PRICE)
                )).given(cardRepository).findLockedById(anyLong());

                // when
                tradeProgressService.confirmTrade(TRADE_ID, "buyer@test.com", true);

                // then
                verify(walletService, times(1)).deductCompositeEscrow(buyer.getId(), (long) PRICE, 0L);
                verify(walletService, times(1)).depositMoney(seller.getId(), (long) PRICE);
            }

            @Test
            @DisplayName("정상 : 머니+포인트 복합 거래 확정 시 deductCompositeEscrow(buyer, money-point, point) 호출")
            void confirmTrade_composite_shouldDeductBuyerEscrowWithPoint() {
                // given — priceGb=10000, point=3000 → moneyToDeduct=7000, pointToDeduct=3000
                willAnswer(inv -> Optional.of(
                        TradeFixture.createDataSentTradeWithPoint(TRADE_ID, buyer, seller, CARD_ID, PRICE, 3000)
                )).given(tradeRepository).findLockedById(anyLong());
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
                given(walletService.deductCompositeEscrow(anyLong(), anyLong(), anyLong()))
                        .willReturn(new CompositeBalanceResult(0L, 0L, 0L, 0L));
                given(walletService.depositMoney(anyLong(), anyLong())).willReturn(20000L);
                given(walletService.depositPoint(anyLong(), anyLong())).willReturn(500L);
                willAnswer(inv -> Optional.of(
                        CardFixture.createTradingCard(CARD_ID, seller, PRICE)
                )).given(cardRepository).findLockedById(anyLong());

                // when
                tradeProgressService.confirmTrade(TRADE_ID, "buyer@test.com", true);

                // then — priceGb(10000) - point(3000) = 7000 머니, 3000 포인트
                verify(walletService, times(1)).deductCompositeEscrow(buyer.getId(), 7000L, 3000L);
                verify(walletService, times(1)).depositMoney(seller.getId(), (long) PRICE);
            }

            @Test
            @DisplayName("정상 : deductCompositeEscrow 장애 시 @Retryable에 의해 재시도")
            void confirmTrade_shouldRetryOnDeductCompositeEscrowFailure() {
                // given
                willAnswer(inv -> Optional.of(
                        TradeFixture.createDataSentTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE)
                )).given(tradeRepository).findLockedById(anyLong());
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
                willAnswer(inv -> Optional.of(
                        CardFixture.createTradingCard(CARD_ID, seller, PRICE)
                )).given(cardRepository).findLockedById(anyLong());
                given(walletService.deductCompositeEscrow(anyLong(), anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        });

                // when & then
                assertThatThrownBy(() ->
                        tradeProgressService.confirmTrade(TRADE_ID, "buyer@test.com", true)
                ).isInstanceOf(TransientDataAccessException.class);

                verify(walletService, times(3)).deductCompositeEscrow(anyLong(), anyLong(), anyLong());
                // depositMoney는 deductCompositeEscrow 성공 이후에 호출되므로, 에스크로 차감 실패 시 미호출
                verify(walletService, never()).depositMoney(anyLong(), anyLong());
            }
        }

        @Nested
        @DisplayName("재시도 동작")
        class RetryBehaviorTest {

            @Test
            @DisplayName("정상 : TransientDataAccessException 발생 시 최대 3회 재시도")
            void confirmTrade_shouldRetryOnTransientDataAccessException() {
                // given — 각 재시도마다 DATA_SENT 상태의 새 Trade 객체 반환
                willAnswer(inv -> Optional.of(
                        TradeFixture.createDataSentTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE)
                )).given(tradeRepository).findLockedById(anyLong());
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
                willAnswer(inv -> Optional.of(
                        CardFixture.createTradingCard(CARD_ID, seller, PRICE)
                )).given(cardRepository).findLockedById(anyLong());
                given(walletService.deductCompositeEscrow(anyLong(), anyLong(), anyLong()))
                        .willReturn(new CompositeBalanceResult(0L, 0L, 0L, 0L));
                given(walletService.depositMoney(anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        });

                // when & then
                assertThatThrownBy(() ->
                        tradeProgressService.confirmTrade(TRADE_ID, "buyer@test.com", true)
                ).isInstanceOf(TransientDataAccessException.class);

                verify(walletService, times(3)).depositMoney(anyLong(), anyLong());
            }

            @Test
            @DisplayName("정상 : 2회 실패 후 3회차에 성공")
            void confirmTrade_shouldSucceedOnThirdAttempt() {
                // given
                willAnswer(inv -> Optional.of(
                        TradeFixture.createDataSentTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE)
                )).given(tradeRepository).findLockedById(anyLong());
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
                willAnswer(inv -> Optional.of(
                        CardFixture.createTradingCard(CARD_ID, seller, PRICE)
                )).given(cardRepository).findLockedById(anyLong());
                given(walletService.deductCompositeEscrow(anyLong(), anyLong(), anyLong()))
                        .willReturn(new CompositeBalanceResult(0L, 0L, 0L, 0L));
                given(walletService.depositMoney(anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        })
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        })
                        .willReturn(20000L);
                given(walletService.depositPoint(anyLong(), anyLong())).willReturn(500L);

                // when
                tradeProgressService.confirmTrade(TRADE_ID, "buyer@test.com", true);

                // then
                verify(walletService, times(3)).depositMoney(anyLong(), anyLong());
            }

            @Test
            @DisplayName("정상 : @Recover 호출 시 TradeAlertService.alertConfirmTradeFailure() 발동")
            void confirmTrade_shouldSendAlertOnRecovery() {
                // given — 3회 모두 실패
                willAnswer(inv -> Optional.of(
                        TradeFixture.createDataSentTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE)
                )).given(tradeRepository).findLockedById(anyLong());
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
                willAnswer(inv -> Optional.of(
                        CardFixture.createTradingCard(CARD_ID, seller, PRICE)
                )).given(cardRepository).findLockedById(anyLong());
                given(walletService.deductCompositeEscrow(anyLong(), anyLong(), anyLong()))
                        .willReturn(new CompositeBalanceResult(0L, 0L, 0L, 0L));
                given(walletService.depositMoney(anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        });

                // when & then
                assertThatThrownBy(() ->
                        tradeProgressService.confirmTrade(TRADE_ID, "buyer@test.com", true)
                ).isInstanceOf(RuntimeException.class);

                verify(tradeAlertService, times(1)).alertConfirmTradeFailure(anyLong(), anyString(), any());
            }
        }
    }
}
