package com.ureca.snac.trade.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.board.entity.Card;
import com.ureca.snac.board.repository.CardRepository;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.support.RetryTestSupport;
import com.ureca.snac.trade.service.TradeAlertService;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.WalletFixture;
import com.ureca.snac.trade.controller.request.CreateRealTimeTradePaymentRequest;
import com.ureca.snac.trade.controller.request.CreateTradeRequest;
import com.ureca.snac.trade.entity.Trade;
import com.ureca.snac.trade.fixture.CardFixture;
import com.ureca.snac.trade.fixture.TradeFixture;
import com.ureca.snac.trade.repository.TradeRepository;
import com.ureca.snac.trade.service.interfaces.TradeInitiationService;
import com.ureca.snac.wallet.entity.Wallet;
import com.ureca.snac.wallet.repository.WalletRepository;
import com.ureca.snac.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.retry.ExhaustedRetryException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * TradeInitiationServiceImpl 단위 테스트 (Spring Support)
 *
 * @Retryable AOP 동작 검증을 위해 Spring Context를 로드하지만,
 * 모든 협력 객체는 Mock으로 대체하여 단위 기능을 검증함.
 */
@DisplayName("TradeInitiationServiceImpl 단위 테스트")
class TradeInitiationServiceImplTest extends RetryTestSupport {

    @Autowired
    private TradeInitiationService tradeInitiationService;

    @MockitoBean
    private TradeRepository tradeRepository;

    @MockitoBean
    private MemberRepository memberRepository;

    @MockitoBean
    private WalletRepository walletRepository;

    @MockitoBean
    private CardRepository cardRepository;

    @MockitoBean
    private WalletService walletService;

    @MockitoBean
    private AssetRecorder assetRecorder;

    @MockitoBean
    private TradeAlertService tradeAlertService;

    // SlackNotifier는 RetryTestSupport에서 @MockitoBean으로 등록됨 (상속)

    private Member buyer;
    private Member seller;
    private Card tradingCard;
    private Card sellingCard;
    private Trade acceptedTrade;
    private Wallet wallet;

    private static final Long TRADE_ID = 1L;
    private static final Long CARD_ID = 1L;
    private static final int PRICE = 10000;

    @BeforeEach
    void setUp() {
        buyer = MemberFixture.createMember(1L);
        seller = MemberFixture.createMember(2L);
        tradingCard = CardFixture.createTradingCard(CARD_ID, seller, PRICE);
        sellingCard = CardFixture.createSellingCard(CARD_ID, seller, PRICE);
        acceptedTrade = TradeFixture.createAcceptedTrade(TRADE_ID, buyer, CARD_ID, PRICE);
        wallet = WalletFixture.createWalletWithId(1L, buyer);
    }

    @Nested
    @DisplayName("payRealTimeTrade 메서드")
    class PayRealTimeTradeTest {

        private CreateRealTimeTradePaymentRequest request;

        @BeforeEach
        void setUpRequest() {
            request = new CreateRealTimeTradePaymentRequest();
            request.setTradeId(TRADE_ID);
            request.setMoney(PRICE);
            request.setPoint(0);
        }

        @Nested
        @DisplayName("재시도 동작")
        class RetryBehaviorTest {

            @Test
            @DisplayName("정상 : TransientDataAccessException 발생 시 최대 3회 재시도")
            void payRealTimeTrade_shouldRetryOnTransientDataAccessException() {
                // given
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
                given(tradeRepository.findLockedById(anyLong())).willReturn(Optional.of(acceptedTrade));
                given(cardRepository.findById(anyLong())).willReturn(Optional.of(tradingCard));
                given(walletRepository.findByMemberIdWithLock(anyLong())).willReturn(Optional.of(wallet));
                given(walletService.withdrawMoney(anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {});

                // when & then
                assertThatThrownBy(() ->
                        tradeInitiationService.payRealTimeTrade(request, "buyer@test.com")
                ).isInstanceOf(TransientDataAccessException.class);

                verify(walletService, times(3)).withdrawMoney(anyLong(), anyLong());
            }

            @Test
            @DisplayName("정상 : 2회 실패 후 3회차에 성공")
            void payRealTimeTrade_shouldSucceedOnThirdAttempt() {
                // given
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
                given(tradeRepository.findLockedById(anyLong())).willReturn(Optional.of(acceptedTrade));
                given(cardRepository.findById(anyLong())).willReturn(Optional.of(tradingCard));
                given(walletRepository.findByMemberIdWithLock(anyLong())).willReturn(Optional.of(wallet));
                given(walletService.withdrawMoney(anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {})
                        .willThrow(new TransientDataAccessException("DB timeout") {})
                        .willReturn(5000L);
                given(tradeRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

                // when
                tradeInitiationService.payRealTimeTrade(request, "buyer@test.com");

                // then
                verify(walletService, times(3)).withdrawMoney(anyLong(), anyLong());
            }

            @Test
            @DisplayName("정상 : @Recover 호출 시 TradeAlertService.alertPayRealTimeTradeFailure() 발동")
            void payRealTimeTrade_shouldSendAlertOnRecovery() {
                // given - 3회 모두 실패
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
                given(tradeRepository.findLockedById(anyLong())).willReturn(Optional.of(acceptedTrade));
                given(cardRepository.findById(anyLong())).willReturn(Optional.of(tradingCard));
                given(walletRepository.findByMemberIdWithLock(anyLong())).willReturn(Optional.of(wallet));
                given(walletService.withdrawMoney(anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {});

                // when & then
                assertThatThrownBy(() ->
                        tradeInitiationService.payRealTimeTrade(request, "buyer@test.com")
                ).isInstanceOf(RuntimeException.class);

                verify(tradeAlertService, times(1)).alertPayRealTimeTradeFailure(anyLong(), anyString(), any());
            }

            @Test
            @DisplayName("예외 : 재시도 불가 예외는 1회만 호출 후 즉시 전파")
            void payRealTimeTrade_shouldNotRetryOnNonRetryableException() {
                // given
                given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
                given(tradeRepository.findLockedById(anyLong())).willReturn(Optional.of(acceptedTrade));
                given(cardRepository.findById(anyLong())).willReturn(Optional.of(tradingCard));
                given(walletRepository.findByMemberIdWithLock(anyLong())).willReturn(Optional.of(wallet));
                given(walletService.withdrawMoney(anyLong(), anyLong()))
                        .willThrow(new IllegalStateException("non-retryable error"));

                // when & then
                // Spring Retry가 @Recover 메서드를 탐색하다가 매칭 실패 시 ExhaustedRetryException으로 래핑함.
                // 핵심 보장: 재시도 없이 1회만 호출됨을 검증.
                assertThatThrownBy(() ->
                        tradeInitiationService.payRealTimeTrade(request, "buyer@test.com")
                ).isInstanceOfAny(IllegalStateException.class, ExhaustedRetryException.class);

                verify(walletService, times(1)).withdrawMoney(anyLong(), anyLong());
            }
        }
    }

    @Nested
    @DisplayName("createSellTrade 메서드")
    class CreateSellTradeTest {

        private CreateTradeRequest request;

        @BeforeEach
        void setUpRequest() {
            request = new CreateTradeRequest();
            request.setCardId(CARD_ID);
            request.setMoney(PRICE);
            request.setPoint(0);
        }

        @Test
        @DisplayName("정상 : TransientDataAccessException 발생 시 최대 3회 재시도")
        void createSellTrade_shouldRetryOnTransientDataAccessException() {
            // given
            given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
            given(cardRepository.findLockedById(anyLong())).willReturn(Optional.of(sellingCard));
            given(walletService.withdrawMoney(anyLong(), anyLong()))
                    .willThrow(new TransientDataAccessException("DB timeout") {});

            // when & then
            assertThatThrownBy(() ->
                    tradeInitiationService.createSellTrade(request, "buyer@test.com")
            ).isInstanceOf(TransientDataAccessException.class);

            verify(walletService, times(3)).withdrawMoney(anyLong(), anyLong());
        }

        @Test
        @DisplayName("정상 : @Recover 호출 시 TradeAlertService.alertCreateTradeFailure() 발동")
        void createSellTrade_shouldSendAlertOnRecovery() {
            // given - 3회 모두 실패
            given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
            given(cardRepository.findLockedById(anyLong())).willReturn(Optional.of(sellingCard));
            given(walletService.withdrawMoney(anyLong(), anyLong()))
                    .willThrow(new TransientDataAccessException("DB timeout") {});

            // when & then
            assertThatThrownBy(() ->
                    tradeInitiationService.createSellTrade(request, "buyer@test.com")
            ).isInstanceOf(RuntimeException.class);

            verify(tradeAlertService, times(1)).alertCreateTradeFailure(anyLong(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("createBuyTrade 메서드")
    class CreateBuyTradeTest {

        private CreateTradeRequest request;
        private Card pendingCard;

        @BeforeEach
        void setUpRequest() {
            request = new CreateTradeRequest();
            request.setCardId(CARD_ID);
            request.setMoney(PRICE);
            request.setPoint(0);
            pendingCard = CardFixture.createPendingCard(CARD_ID, buyer, PRICE);
        }

        @Test
        @DisplayName("정상 : TransientDataAccessException 발생 시 최대 3회 재시도")
        void createBuyTrade_shouldRetryOnTransientDataAccessException() {
            // given
            given(memberRepository.findByEmail(anyString())).willReturn(Optional.of(buyer));
            given(cardRepository.findLockedById(anyLong())).willReturn(Optional.of(pendingCard));
            given(walletService.withdrawMoney(anyLong(), anyLong()))
                    .willThrow(new TransientDataAccessException("DB timeout") {});

            // when & then
            assertThatThrownBy(() ->
                    tradeInitiationService.createBuyTrade(request, "buyer@test.com")
            ).isInstanceOf(TransientDataAccessException.class);

            verify(walletService, times(3)).withdrawMoney(anyLong(), anyLong());
        }
    }
}
