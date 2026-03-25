package com.ureca.snac.trade.scheduler;

import com.ureca.snac.asset.entity.AssetType;
import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.board.entity.Card;
import com.ureca.snac.board.exception.CardNotFoundException;
import com.ureca.snac.board.repository.CardRepository;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.trade.entity.PenaltyReason;
import com.ureca.snac.trade.entity.Trade;
import com.ureca.snac.trade.entity.TradeStatus;
import com.ureca.snac.trade.service.TradeAlertService;
import com.ureca.snac.trade.service.interfaces.PenaltyService;
import com.ureca.snac.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeAutoItemProcessor {

    private final CardRepository cardRepository;
    private final WalletService walletService;
    private final AssetRecorder assetRecorder;
    private final PenaltyService penaltyService;
    private final TradeAlertService tradeAlertService;

    /**
     * 판매자 미전송으로 인한 자동 환불 단건 처리
     * 각 재시도는 새로운 트랜잭션으로 실행됨 (Retry → Transaction 인터셉터 순서 보장)
     */
    @Retryable(
            retryFor = {TransientDataAccessException.class},
            maxAttemptsExpression = "${retry.trade.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${retry.trade.delay}",
                    multiplierExpression = "${retry.trade.multiplier}"
            )
    )
    @Transactional
    public void processRefund(Trade trade) {
        Card card = findLockedCard(trade.getCardId());
        Member buyer = trade.getBuyer();

        long moneyToRefund = trade.getPriceGb() - trade.getPoint();
        if (moneyToRefund > 0) {
            long moneyFinalBalance = walletService.depositMoney(buyer.getId(), moneyToRefund);
            String title = String.format("%s %dGB 자동 환불",
                    card.getCarrier().name(), card.getDataAmount());
            assetRecorder.recordTradeCancelRefund(
                    buyer.getId(), trade.getId(), title, AssetType.MONEY, moneyToRefund, moneyFinalBalance);
        }

        long pointToRefund = trade.getPoint();
        if (pointToRefund > 0) {
            long pointFinalBalance = walletService.depositPoint(buyer.getId(), pointToRefund);
            String title = String.format("%s %dGB 자동 포인트 환불",
                    card.getCarrier().name(), card.getDataAmount());
            assetRecorder.recordTradeCancelRefund(
                    buyer.getId(), trade.getId(), title, AssetType.POINT, pointToRefund, pointFinalBalance);
        }

        trade.cancel(trade.getBuyer());
        penaltyService.givePenalty(trade.getSeller().getEmail(), PenaltyReason.AUTO_DELAY);
        log.info("[AUTO_REFUND] trade {} 환불 완료", trade.getId());
    }

    /**
     * processRefund / processPayout 공통 @Recover
     * 두 메서드의 파라미터 타입이 동일하므로 단일 메서드로 처리.
     * Trade 상태로 실패 유형을 구분함 (TX 롤백으로 인해 wallet 호출 전 원래 상태가 유지됨).
     */
    @Recover
    public void recoverProcessItem(DataAccessException e, Trade trade) {
        switch (trade.getStatus()) {
            case PAYMENT_CONFIRMED -> tradeAlertService.alertAutoRefundFailure(trade.getId(), e);
            case DATA_SENT -> tradeAlertService.alertAutoPayoutFailure(trade.getId(), e);
            default -> log.error("[자동처리 @Recover 예상치 못한 상태] tradeId: {}, status: {}",
                    trade.getId(), trade.getStatus(), e);
        }
    }

    /**
     * 구매자 미확정으로 인한 자동 정산 단건 처리
     */
    @Retryable(
            retryFor = {TransientDataAccessException.class},
            maxAttemptsExpression = "${retry.trade.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${retry.trade.delay}",
                    multiplierExpression = "${retry.trade.multiplier}"
            )
    )
    @Transactional
    public void processPayout(Trade trade) {
        Card card = findLockedCard(trade.getCardId());
        Member seller = trade.getSeller();

        long amountToDeposit = trade.getPriceGb();
        long finalBalance = walletService.depositMoney(seller.getId(), amountToDeposit);

        String title = String.format("%s %dGB 자동 정산",
                card.getCarrier().name(), card.getDataAmount());
        assetRecorder.recordTradeSell(seller.getId(), trade.getId(), title, amountToDeposit, finalBalance);

        trade.changeStatus(TradeStatus.COMPLETED);
        penaltyService.givePenalty(trade.getBuyer().getEmail(), PenaltyReason.AUTO_DELAY);
        log.info("[AUTO_PAYOUT] trade {} 판매자 정산 완료", trade.getId());
    }

    private Card findLockedCard(Long cardId) {
        return cardRepository.findLockedById(cardId).orElseThrow(CardNotFoundException::new);
    }
}
