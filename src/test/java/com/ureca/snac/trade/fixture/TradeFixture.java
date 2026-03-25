package com.ureca.snac.trade.fixture;

import com.ureca.snac.board.entity.constants.Carrier;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.TestReflectionUtils;
import com.ureca.snac.trade.entity.Trade;
import com.ureca.snac.trade.entity.TradeStatus;
import com.ureca.snac.trade.entity.TradeType;

public class TradeFixture {

    public static Trade createAcceptedTrade(Long tradeId, Member buyer, Long cardId, int priceGb) {
        Trade trade = Trade.builder()
                .cardId(cardId)
                .buyer(buyer)
                .carrier(Carrier.SKT)
                .priceGb(priceGb)
                .dataAmount(10)
                .status(TradeStatus.ACCEPTED)
                .tradeType(TradeType.REALTIME)
                .phone("01012345678")
                .point(0)
                .build();
        TestReflectionUtils.setField(trade, "id", tradeId);
        return trade;
    }

    public static Trade createDataSentTrade(Long tradeId, Member buyer, Member seller, Long cardId, int priceGb) {
        Trade trade = Trade.builder()
                .cardId(cardId)
                .buyer(buyer)
                .seller(seller)
                .carrier(Carrier.SKT)
                .priceGb(priceGb)
                .dataAmount(10)
                .status(TradeStatus.DATA_SENT)
                .tradeType(TradeType.REALTIME)
                .phone("01012345678")
                .point(0)
                .build();
        TestReflectionUtils.setField(trade, "id", tradeId);
        return trade;
    }

    public static Trade createPaymentConfirmedTrade(Long tradeId, Member buyer, Long cardId, int priceGb) {
        Trade trade = Trade.builder()
                .cardId(cardId)
                .buyer(buyer)
                .carrier(Carrier.SKT)
                .priceGb(priceGb)
                .dataAmount(10)
                .status(TradeStatus.PAYMENT_CONFIRMED)
                .tradeType(TradeType.REALTIME)
                .phone("01012345678")
                .point(0)
                .build();
        TestReflectionUtils.setField(trade, "id", tradeId);
        return trade;
    }

    public static Trade createPaymentConfirmedTrade(Long tradeId, Member buyer, Member seller, Long cardId, int priceGb) {
        Trade trade = Trade.builder()
                .cardId(cardId)
                .buyer(buyer)
                .seller(seller)
                .carrier(Carrier.SKT)
                .priceGb(priceGb)
                .dataAmount(10)
                .status(TradeStatus.PAYMENT_CONFIRMED)
                .tradeType(TradeType.REALTIME)
                .phone("01012345678")
                .point(0)
                .build();
        TestReflectionUtils.setField(trade, "id", tradeId);
        return trade;
    }
}
