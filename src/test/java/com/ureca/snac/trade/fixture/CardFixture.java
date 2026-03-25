package com.ureca.snac.trade.fixture;

import com.ureca.snac.board.entity.Card;
import com.ureca.snac.board.entity.constants.CardCategory;
import com.ureca.snac.board.entity.constants.Carrier;
import com.ureca.snac.board.entity.constants.SellStatus;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.TestReflectionUtils;

public class CardFixture {

    public static Card createSellingCard(Long cardId, Member owner, int price) {
        Card card = Card.builder()
                .member(owner)
                .sellStatus(SellStatus.SELLING)
                .cardCategory(CardCategory.SELL)
                .carrier(Carrier.SKT)
                .dataAmount(10)
                .price(price)
                .build();
        TestReflectionUtils.setField(card, "id", cardId);
        return card;
    }

    public static Card createTradingCard(Long cardId, Member owner, int price) {
        Card card = Card.builder()
                .member(owner)
                .sellStatus(SellStatus.TRADING)
                .cardCategory(CardCategory.SELL)
                .carrier(Carrier.SKT)
                .dataAmount(10)
                .price(price)
                .build();
        TestReflectionUtils.setField(card, "id", cardId);
        return card;
    }

    public static Card createPendingCard(Long cardId, Member owner, int price) {
        Card card = Card.builder()
                .member(owner)
                .sellStatus(SellStatus.PENDING)
                .cardCategory(CardCategory.BUY)
                .carrier(Carrier.SKT)
                .dataAmount(10)
                .price(price)
                .build();
        TestReflectionUtils.setField(card, "id", cardId);
        return card;
    }
}
