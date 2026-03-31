package com.ureca.snac.trade.service;

import com.ureca.snac.asset.entity.AssetType;
import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.board.entity.Card;
import com.ureca.snac.board.repository.CardRepository;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.trade.entity.Trade;
import com.ureca.snac.trade.fixture.CardFixture;
import com.ureca.snac.trade.fixture.TradeFixture;
import com.ureca.snac.trade.repository.TradeCancelRepository;
import com.ureca.snac.trade.repository.TradeRepository;
import com.ureca.snac.trade.service.interfaces.PenaltyService;
import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * TradeCancelServiceImpl 단위 테스트
 *
 * @Retryable 미적용 서비스이므로 순수 Mockito 단위 테스트 사용.
 * 에스크로 환불 메서드(releaseCompositeEscrow) 호출 검증에 집중.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TradeCancelServiceImpl 단위 테스트")
class TradeCancelServiceImplTest {

    @InjectMocks
    private TradeCancelServiceImpl tradeCancelService;

    @Mock
    private TradeCancelRepository cancelRepo;
    @Mock
    private CardRepository cardRepo;
    @Mock
    private TradeRepository tradeRepo;
    @Mock
    private MemberRepository memberRepo;
    @Mock
    private PenaltyService penaltyService;
    @Mock
    private WalletService walletService;
    @Mock
    private AssetRecorder assetRecorder;

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
    @DisplayName("refundBuyerEscrow 메서드")
    class RefundToBuyerAndPublishEventTest {

        @Test
        @DisplayName("정상 : 머니만 사용한 거래 취소 시 releaseCompositeEscrow(money, 0) 호출")
        void refund_moneyOnly_shouldCallReleaseCompositeEscrow() {
            // given
            Trade trade = TradeFixture.createPaymentConfirmedTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE);
            Card card = CardFixture.createTradingCard(CARD_ID, seller, PRICE);

            given(walletService.releaseCompositeEscrow(anyLong(), anyLong(), anyLong()))
                    .willReturn(new CompositeBalanceResult(PRICE, 0L, 0L, 0L));

            // when
            tradeCancelService.refundBuyerEscrow(trade, card, buyer);

            // then
            verify(walletService, times(1)).releaseCompositeEscrow(buyer.getId(), PRICE, 0L);
            verify(assetRecorder, times(1)).recordTradeCancelRefund(
                    eq(buyer.getId()), eq(TRADE_ID), anyString(), eq(AssetType.MONEY), eq((long) PRICE), eq((long) PRICE));
            verify(assetRecorder, never()).recordTradeCancelRefund(
                    anyLong(), anyLong(), anyString(), eq(AssetType.POINT), anyLong(), anyLong());
        }

        @Test
        @DisplayName("정상 : 포인트만 사용한 거래 취소 시 releaseCompositeEscrow(0, point) 호출")
        void refund_pointOnly_shouldCallReleaseCompositeEscrow() {
            // given: priceGb=5000, point=5000 → moneyToRefund=0, pointToRefund=5000
            Trade trade = TradeFixture.createPaymentConfirmedTradeWithPoint(TRADE_ID, buyer, seller, CARD_ID, 5000, 5000);
            Card card = CardFixture.createTradingCard(CARD_ID, seller, 5000);

            given(walletService.releaseCompositeEscrow(anyLong(), anyLong(), anyLong()))
                    .willReturn(new CompositeBalanceResult(0L, 0L, 5000L, 0L));

            // when
            tradeCancelService.refundBuyerEscrow(trade, card, buyer);

            // then
            verify(walletService, times(1)).releaseCompositeEscrow(buyer.getId(), 0L, 5000L);
            verify(assetRecorder, never()).recordTradeCancelRefund(
                    anyLong(), anyLong(), anyString(), eq(AssetType.MONEY), anyLong(), anyLong());
            verify(assetRecorder, times(1)).recordTradeCancelRefund(
                    eq(buyer.getId()), eq(TRADE_ID), anyString(), eq(AssetType.POINT), eq(5000L), eq(5000L));
        }

        @Test
        @DisplayName("정상 : 복합 결제(머니+포인트) 취소 시 releaseCompositeEscrow(money, point) 호출")
        void refund_composite_shouldCallReleaseCompositeEscrow() {
            // given: priceGb=10000, point=3000 → moneyToRefund=7000, pointToRefund=3000
            Trade trade = TradeFixture.createPaymentConfirmedTradeWithPoint(TRADE_ID, buyer, seller, CARD_ID, 10000, 3000);
            Card card = CardFixture.createTradingCard(CARD_ID, seller, 10000);

            given(walletService.releaseCompositeEscrow(anyLong(), anyLong(), anyLong()))
                    .willReturn(new CompositeBalanceResult(7000L, 0L, 3000L, 0L));

            // when
            tradeCancelService.refundBuyerEscrow(trade, card, buyer);

            // then
            verify(walletService, times(1)).releaseCompositeEscrow(buyer.getId(), 7000L, 3000L);
            verify(assetRecorder, times(1)).recordTradeCancelRefund(
                    eq(buyer.getId()), eq(TRADE_ID), anyString(), eq(AssetType.MONEY), eq(7000L), eq(7000L));
            verify(assetRecorder, times(1)).recordTradeCancelRefund(
                    eq(buyer.getId()), eq(TRADE_ID), anyString(), eq(AssetType.POINT), eq(3000L), eq(3000L));
        }
    }

    @Nested
    @DisplayName("cancelRealTimeTrade 메서드")
    class CancelRealTimeTradeTest {

        @Test
        @DisplayName("정상 : PAYMENT_CONFIRMED 상태 취소 시 에스크로 환불 호출")
        void cancelRealTimeTrade_whenPaymentConfirmed_shouldReleaseEscrow() {
            // given
            Trade trade = TradeFixture.createPaymentConfirmedTrade(TRADE_ID, buyer, seller, CARD_ID, PRICE);
            Card card = CardFixture.createTradingCard(CARD_ID, seller, PRICE);

            given(memberRepo.findByEmail(anyString())).willReturn(Optional.of(buyer));
            given(tradeRepo.findLockedById(anyLong())).willReturn(Optional.of(trade));
            given(cardRepo.findLockedById(anyLong())).willReturn(Optional.of(card));
            given(walletService.releaseCompositeEscrow(anyLong(), anyLong(), anyLong()))
                    .willReturn(new CompositeBalanceResult((long) PRICE, 0L, 0L, 0L));

            // when
            tradeCancelService.cancelRealTimeTrade(TRADE_ID, buyer.getEmail(), com.ureca.snac.trade.entity.CancelReason.BUYER_CHANGE_MIND);

            // then
            verify(walletService, times(1)).releaseCompositeEscrow(buyer.getId(), (long) PRICE, 0L);
        }

        @Test
        @DisplayName("정상 : ACCEPTED 상태(결제 전) 취소 시 에스크로 환불 미호출")
        void cancelRealTimeTrade_whenAccepted_shouldNotReleaseEscrow() {
            // given
            Trade trade = TradeFixture.createAcceptedTradeWithSeller(TRADE_ID, buyer, seller, CARD_ID, PRICE);
            Card card = CardFixture.createTradingCard(CARD_ID, seller, PRICE);

            given(memberRepo.findByEmail(anyString())).willReturn(Optional.of(buyer));
            given(tradeRepo.findLockedById(anyLong())).willReturn(Optional.of(trade));
            given(cardRepo.findLockedById(anyLong())).willReturn(Optional.of(card));

            // when
            tradeCancelService.cancelRealTimeTrade(TRADE_ID, buyer.getEmail(), com.ureca.snac.trade.entity.CancelReason.BUYER_CHANGE_MIND);

            // then: 결제 전 상태이므로 에스크로 환불 없음
            verify(walletService, never()).releaseCompositeEscrow(anyLong(), anyLong(), anyLong());
        }
    }
}
