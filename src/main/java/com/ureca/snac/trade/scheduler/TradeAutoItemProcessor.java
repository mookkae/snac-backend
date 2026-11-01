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
import com.ureca.snac.trade.exception.TradeNotFoundException;
import com.ureca.snac.trade.repository.TradeRepository;
import com.ureca.snac.trade.service.TradeAlertService;
import com.ureca.snac.trade.service.interfaces.PenaltyService;
import com.ureca.snac.wallet.dto.CompositeBalanceResult;
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

    private final TradeRepository tradeRepository;
    private final CardRepository cardRepository;
    private final WalletService walletService;
    private final AssetRecorder assetRecorder;
    private final PenaltyService penaltyService;
    private final TradeAlertService tradeAlertService;

    /**
     * 판매자 미전송으로 인한 자동 환불 단건 처리
     * 각 재시도는 새로운 트랜잭션으로 실행됨 (Retry → Transaction 인터셉터 순서 보장)
     * 목록 조회 뒤 상태가 바뀔 수 있어 잠근 뒤 다시 확인한다
     */
    @Retryable(
            recover = "recoverRefund",
            retryFor = {TransientDataAccessException.class},
            maxAttemptsExpression = "${retry.trade.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${retry.trade.delay}",
                    multiplierExpression = "${retry.trade.multiplier}"
            )
    )
    @Transactional
    public void processRefund(Long tradeId) {
        Trade trade = findLockedTrade(tradeId);
        if (trade.getStatus() != TradeStatus.PAYMENT_CONFIRMED) {
            log.info("[AUTO_REFUND] trade {} 상태 변경되어 건너뜀. status: {}", tradeId, trade.getStatus());
            return;
        }

        Card card = findLockedCard(trade.getCardId());
        Member buyer = trade.getBuyer();

        int point = trade.getPointOrZero();
        long moneyToRefund = trade.getMoneyAmount();

        CompositeBalanceResult result =
                walletService.cancelCompositeEscrow(buyer.getId(), moneyToRefund, point);

        if (moneyToRefund > 0) {
            String title = String.format("%s %dGB 자동 환불",
                    card.getCarrier().name(), card.getDataAmount());
            assetRecorder.recordTradeCancelRefund(
                    buyer.getId(), trade.getId(), title, AssetType.MONEY, moneyToRefund, result.moneyBalance());
        }

        if (point > 0) {
            String title = String.format("%s %dGB 자동 포인트 환불",
                    card.getCarrier().name(), card.getDataAmount());
            assetRecorder.recordTradeCancelRefund(
                    buyer.getId(), trade.getId(), title, AssetType.POINT, (long) point, result.pointBalance());
        }

        trade.cancel(trade.getBuyer());
        penaltyService.givePenalty(trade.getSeller().getEmail(), PenaltyReason.AUTO_DELAY);
        log.info("[AUTO_REFUND] trade {} 환불 완료", trade.getId());
    }

    /**
     * 구매자 미확정으로 인한 자동 정산 단건 처리
     * 목록 조회 뒤 상태가 바뀔 수 있어 잠근 뒤 다시 확인한다
     */
    @Retryable(
            recover = "recoverPayout",
            retryFor = {TransientDataAccessException.class},
            maxAttemptsExpression = "${retry.trade.max-attempts}",
            backoff = @Backoff(
                    delayExpression = "${retry.trade.delay}",
                    multiplierExpression = "${retry.trade.multiplier}"
            )
    )
    @Transactional
    public void processPayout(Long tradeId) {
        Trade trade = findLockedTrade(tradeId);
        if (trade.getStatus() != TradeStatus.DATA_SENT || trade.isAutoConfirmPaused()) {
            log.info("[AUTO_PAYOUT] trade {} 상태 변경되어 건너뜀. status: {}", tradeId, trade.getStatus());
            return;
        }

        Card card = findLockedCard(trade.getCardId());
        Member buyer = trade.getBuyer();
        Member seller = trade.getSeller();

        // 1. 구매자 에스크로 차감 (escrow → 소멸)
        walletService.deductCompositeEscrow(buyer.getId(), trade.getMoneyAmount(), trade.getPointOrZero());

        // 2. 판매자에게 총액 입금
        long amountToDeposit = trade.getPriceGb();
        long finalBalance = walletService.depositMoney(seller.getId(), amountToDeposit);

        String title = String.format("%s %dGB 자동 정산",
                card.getCarrier().name(), card.getDataAmount());
        assetRecorder.recordTradeSell(seller.getId(), trade.getId(), title, amountToDeposit, finalBalance);

        trade.changeStatus(TradeStatus.COMPLETED);
        penaltyService.givePenalty(trade.getBuyer().getEmail(), PenaltyReason.AUTO_DELAY);
        log.info("[AUTO_PAYOUT] trade {} 판매자 정산 완료", trade.getId());
    }

    @Recover
    public void recoverRefund(DataAccessException e, Long tradeId) {
        tradeAlertService.alertAutoRefundFailure(tradeId, e);
    }

    @Recover
    public void recoverPayout(DataAccessException e, Long tradeId) {
        tradeAlertService.alertAutoPayoutFailure(tradeId, e);
    }

    private Trade findLockedTrade(Long tradeId) {
        return tradeRepository.findLockedById(tradeId).orElseThrow(TradeNotFoundException::new);
    }

    private Card findLockedCard(Long cardId) {
        return cardRepository.findLockedById(cardId).orElseThrow(CardNotFoundException::new);
    }
}
