package com.ureca.snac.trade.service;

import com.ureca.snac.asset.entity.TransactionDetail;
import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.board.entity.Card;
import com.ureca.snac.board.exception.CardNotFoundException;
import com.ureca.snac.board.repository.CardRepository;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.member.service.MemberService;
import com.ureca.snac.trade.dto.TradeDto;
import com.ureca.snac.trade.entity.Trade;
import com.ureca.snac.trade.exception.TradeNotFoundException;
import com.ureca.snac.trade.repository.TradeRepository;
import com.ureca.snac.trade.service.interfaces.TradeProgressService;
import com.ureca.snac.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TradeProgressServiceImpl implements TradeProgressService {
    private final TradeRepository tradeRepository;
    private final CardRepository cardRepository;
    private final MemberRepository memberRepository;

    private final WalletService walletService;
    private final AssetRecorder assetRecorder;
    private final MemberService memberService;

    private static final int RATING_SCORE_BONUS = 10;

    @Override
    @Transactional
    public Long sendTradeData(Long tradeId, String username) {
        Member seller = findMember(username);
        Trade trade = findLockedTrade(tradeId);
//        Card card = findLockedCard(trade.getCardId()); -> 불필요한 락 제거

        trade.markDataSent(); // 결제가 완료되지 않은 상태에서는 판매자가 데이터를 전송할 수 없음
        trade.ensureSendPermission(seller);

        return trade.getId();
    }

    // 해당 확정 메서드는 현재 실시간 매칭과 일반 매칭에 사용합니다.
    // 마지막에 상대방이 나간 경우 카드가 삭제되는 문제가 발생하여 거래가 확정이 안되는 문제가 있어 파라미터 hasCard를 추가합니다.
    // 일반 매칭 hasCard == true, 실시간 매칭 == false
    @Override
    @Transactional
    public TradeDto confirmTrade(Long tradeId, String username, Boolean hasCard) {
        Trade trade = findLockedTrade(tradeId);
        Member buyer = findMember(username);
        Member seller = trade.getSeller();

        trade.confirm(buyer); // 거래 상태를 확정으로 변경

        if (hasCard) {
            Card card = findLockedCard(trade.getCardId());
            card.markSoldOut(); // 카드 상태를 판매 완료로 변경
        } else {
            cardRepository.deleteById(trade.getCardId()); // 실시간 매칭에서는 거래가 끝난 경우 카드는 필요 없으므로 삭제
        }

        long amountToDeposit = trade.getPriceGb();
        long finalMoneyBalance = walletService.depositMoney(seller.getId(), amountToDeposit);

        String title = String.format("%s %dGB 판매 대금",
                trade.getCarrier().name(), trade.getDataAmount());
        assetRecorder.recordTradeSell(
                seller.getId(), trade.getId(), title, amountToDeposit, finalMoneyBalance);

        // 포인트
        long amount = TransactionDetail.TRADE_COMPLETION_BONUS.getDefaultAmount();
        long finalPointBalance = walletService.depositPoint(seller.getId(), amount);

        assetRecorder.recordTradeCompletionBonus(
                seller.getId(), trade.getId(), amount, finalPointBalance);

        memberService.addRatingScore(buyer.getId(), RATING_SCORE_BONUS);
        memberService.addRatingScore(seller.getId(), RATING_SCORE_BONUS);

        log.info("[거래 확정 완료] 모든 후처리 작업 성공 . 거래 ID : {}", tradeId);
        return TradeDto.from(trade);
    }

    private Member findMember(String email) {
        return memberRepository.findByEmail(email).orElseThrow(MemberNotFoundException::new);
    }

    private Card findLockedCard(Long cardId) {
        return cardRepository.findLockedById(cardId).orElseThrow(CardNotFoundException::new);
    }

    private Trade findLockedTrade(Long tradeId) {
        return tradeRepository.findLockedById(tradeId).orElseThrow(TradeNotFoundException::new);
    }
}
