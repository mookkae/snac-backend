package com.ureca.snac.trade.scheduler;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.board.repository.CardRepository;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.RetryTestSupport;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.trade.entity.Trade;
import com.ureca.snac.trade.fixture.CardFixture;
import com.ureca.snac.trade.fixture.TradeFixture;
import com.ureca.snac.trade.service.TradeAlertService;
import com.ureca.snac.trade.service.interfaces.PenaltyService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.*;

/**
 * TradeAutoItemProcessor 단위 테스트 (Spring Support)
 *
 * @Retryable AOP 동작 검증을 위해 Spring Context를 로드하지만,
 * 모든 협력 객체는 Mock으로 대체하여 단위 기능을 검증함.
 * @Recover 메서드는 예외를 re-throw하지 않으므로,
 * 재시도 횟수와 Slack 알림을 동일 테스트에서 함께 검증함.
 */
@DisplayName("TradeAutoItemProcessor 단위 테스트")
class TradeAutoItemProcessorTest extends RetryTestSupport {

    @Autowired
    private TradeAutoItemProcessor tradeAutoItemProcessor;

    @MockitoBean
    private CardRepository cardRepository;

    @MockitoBean
    private WalletService walletService;

    @MockitoBean
    private AssetRecorder assetRecorder;

    @MockitoBean
    private PenaltyService penaltyService;

    @MockitoBean
    private TradeAlertService tradeAlertService;

    // SlackNotifier는 RetryTestSupport에서 @MockitoBean으로 등록됨 (상속)

    private Member buyer;
    private Member seller;

    private static final Long TRADE_ID = 1L;
    private static final Long CARD_ID = 1L;
    private static final int PRICE = 10000;

    @BeforeEach
    void setUp() {
        buyer = MemberFixture.createMember(1L);
        seller = MemberFixture.createMember(2L);
    }

    @Nested
    @DisplayName("processRefund 메서드")
    class ProcessRefundTest {

        @Nested
        @DisplayName("재시도 동작")
        class RetryBehaviorTest {

            @Test
            @DisplayName("정상 : TransientDataAccessException 발생 시 최대 3회 재시도 후 @Recover 호출")
            void processRefund_shouldRetry3TimesAndSendSlackOnExhaustion() {
                // given
                Trade trade = TradeFixture.createPaymentConfirmedTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE);
                willAnswer(inv -> Optional.of(
                        CardFixture.createTradingCard(CARD_ID, seller, PRICE)
                )).given(cardRepository).findLockedById(anyLong());
                given(walletService.releaseCompositeEscrow(anyLong(), anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        });

                // when — @Recover가 예외를 삼키므로 정상 반환
                tradeAutoItemProcessor.processRefund(trade);

                // then
                verify(walletService, times(3)).releaseCompositeEscrow(anyLong(), anyLong(), anyLong());
                verify(tradeAlertService, times(1)).alertAutoRefundFailure(anyLong(), any());
            }

            @Test
            @DisplayName("정상 : 2회 실패 후 3회차에 성공, 알림 미발송")
            void processRefund_shouldSucceedOnThirdAttempt() {
                // given
                Trade trade = TradeFixture.createPaymentConfirmedTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE);
                willAnswer(inv -> Optional.of(
                        CardFixture.createTradingCard(CARD_ID, seller, PRICE)
                )).given(cardRepository).findLockedById(anyLong());
                given(walletService.releaseCompositeEscrow(anyLong(), anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        })
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        })
                        .willReturn(new CompositeBalanceResult(PRICE, 0L, 0L, 0L));

                // when
                tradeAutoItemProcessor.processRefund(trade);

                // then
                verify(walletService, times(3)).releaseCompositeEscrow(anyLong(), anyLong(), anyLong());
                verify(tradeAlertService, never()).alertAutoRefundFailure(anyLong(), any());
            }
        }
    }

    @Nested
    @DisplayName("processPayout 메서드")
    class ProcessPayoutTest {

        @Nested
        @DisplayName("재시도 동작")
        class RetryBehaviorTest {

            @Test
            @DisplayName("정상 : TransientDataAccessException 발생 시 최대 3회 재시도 후 @Recover 호출")
            void processPayout_shouldRetry3TimesAndSendSlackOnExhaustion() {
                // given
                Trade trade = TradeFixture.createDataSentTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE);
                willAnswer(inv -> Optional.of(
                        CardFixture.createTradingCard(CARD_ID, seller, PRICE)
                )).given(cardRepository).findLockedById(anyLong());
                given(walletService.deductCompositeEscrow(anyLong(), anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        });

                // when — @Recover가 예외를 삼키므로 정상 반환
                tradeAutoItemProcessor.processPayout(trade);

                // then
                verify(walletService, times(3)).deductCompositeEscrow(anyLong(), anyLong(), anyLong());
                verify(tradeAlertService, times(1)).alertAutoPayoutFailure(anyLong(), any());
            }

            @Test
            @DisplayName("정상 : 2회 실패 후 3회차에 성공, 알림 미발송")
            void processPayout_shouldSucceedOnThirdAttempt() {
                // given
                Trade trade = TradeFixture.createDataSentTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE);
                willAnswer(inv -> Optional.of(
                        CardFixture.createTradingCard(CARD_ID, seller, PRICE)
                )).given(cardRepository).findLockedById(anyLong());
                given(walletService.deductCompositeEscrow(anyLong(), anyLong(), anyLong()))
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        })
                        .willThrow(new TransientDataAccessException("DB timeout") {
                        })
                        .willReturn(new CompositeBalanceResult(0L, 0L, 0L, 0L));
                given(walletService.depositMoney(anyLong(), anyLong())).willReturn(15000L);

                // when
                tradeAutoItemProcessor.processPayout(trade);

                // then
                verify(walletService, times(3)).deductCompositeEscrow(anyLong(), anyLong(), anyLong());
                verify(tradeAlertService, never()).alertAutoPayoutFailure(anyLong(), any());
            }
        }
    }
}
