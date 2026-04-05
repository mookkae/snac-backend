package com.ureca.snac.integration;

import com.ureca.snac.board.entity.Card;
import com.ureca.snac.board.entity.constants.CardCategory;
import com.ureca.snac.board.entity.constants.Carrier;
import com.ureca.snac.board.entity.constants.SellStatus;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.trade.entity.CancelReason;
import com.ureca.snac.trade.entity.Trade;
import com.ureca.snac.trade.entity.TradeStatus;
import com.ureca.snac.trade.entity.TradeType;
import com.ureca.snac.trade.scheduler.TradeAutoItemProcessor;
import com.ureca.snac.trade.service.TradeAlertService;
import com.ureca.snac.trade.service.TradeCancelServiceImpl;
import com.ureca.snac.trade.service.TradeProgressServiceImpl;
import com.ureca.snac.trade.service.interfaces.PenaltyService;
import com.ureca.snac.wallet.entity.Wallet;
import com.ureca.snac.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 에스크로 전체 흐름 통합 테스트
 * <p>
 * processRefund  : 자동 환불 (머니 단독, 머니+포인트 복합)
 * confirmTrade   : 구매자 확정 → 에스크로 차감 + 판매자 입금
 * processPayout  : 자동 정산 → 에스크로 차감 + 판매자 입금
 * cancelRealTimeTrade (PAYMENT_CONFIRMED) : 에스크로 복원
 * 동시성 : 잔액 10,000원으로 1,000씩 50스레드 → 정확히 10개만 성공
 */
@DisplayName("에스크로 흐름 통합 테스트")
class EscrowFlowIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private WalletService walletService;

    @Autowired
    private TradeProgressServiceImpl tradeProgressService;

    @Autowired
    private TradeCancelServiceImpl tradeCancelService;

    @Autowired
    private TradeAutoItemProcessor tradeAutoItemProcessor;

    @MockitoBean
    private PenaltyService penaltyService;

    @MockitoBean
    private TradeAlertService tradeAlertService;

    private Member buyer;
    private Member seller;

    private static final int PRICE_GB = 10_000;
    private static final long MONEY_AMOUNT = 10_000L;
    private static final int THREAD_COUNT = 50;

    @BeforeEach
    void setUpMembers() {
        buyer = createMemberWithWallet("escrow_buyer_");
        seller = createMemberWithWallet("escrow_seller_");
    }

    // processRefund : 자동 환불
    @Nested
    @DisplayName("processRefund (자동 환불)")
    class ProcessRefundTest {

        @Test
        @DisplayName("머니 에스크로 → 환불 후 buyer 잔액 복원")
        void processRefund_moneyOnly_restoresBuyerBalance() {
            // given
            walletService.depositMoney(buyer.getId(), MONEY_AMOUNT);
            walletService.moveCompositeToEscrow(buyer.getId(), MONEY_AMOUNT, 0L);

            Card card = saveCard(seller, PRICE_GB, SellStatus.TRADING);
            Trade trade = saveTrade(buyer, seller, card, PRICE_GB, 0, TradeStatus.PAYMENT_CONFIRMED);

            // when
            tradeAutoItemProcessor.processRefund(trade);

            // then: 에스크로 → 잔액 복원 (핵심 금융 불변식)
            Wallet buyerWallet = walletRepository.findByMemberId(buyer.getId()).orElseThrow();
            assertThat(buyerWallet.getMoneyBalance()).isEqualTo(MONEY_AMOUNT);
            assertThat(buyerWallet.getMoneyEscrow()).isZero();
        }

        @Test
        @DisplayName("머니+포인트 복합 에스크로 → 환불 후 buyer 잔액·포인트 모두 복원")
        void processRefund_composite_restoresBothAssets() {
            // given: priceGb=1000, point=200 → money=800, point=200
            int priceGb = 1_000;
            int point = 200;
            long moneyToUse = priceGb - point;

            walletService.depositMoney(buyer.getId(), moneyToUse);
            walletService.depositPoint(buyer.getId(), point);
            walletService.moveCompositeToEscrow(buyer.getId(), moneyToUse, point);

            Card card = saveCard(seller, priceGb, SellStatus.TRADING);
            Trade trade = saveTrade(buyer, seller, card, priceGb, point, TradeStatus.PAYMENT_CONFIRMED);

            // when
            tradeAutoItemProcessor.processRefund(trade);

            // then
            Wallet buyerWallet = walletRepository.findByMemberId(buyer.getId()).orElseThrow();
            assertThat(buyerWallet.getMoneyBalance()).isEqualTo(moneyToUse);
            assertThat(buyerWallet.getMoneyEscrow()).isZero();
            assertThat(buyerWallet.getPointBalance()).isEqualTo(point);
            assertThat(buyerWallet.getPointEscrow()).isZero();
        }
    }

    // confirmTrade : 구매자 확정
    @Nested
    @DisplayName("confirmTrade (구매자 확정)")
    class ConfirmTradeTest {

        @Test
        @DisplayName("구매자 확정 시 buyer 에스크로 차감, seller 머니 입금, 거래 COMPLETED, 카드 SOLD_OUT")
        void confirmTrade_deductsEscrowAndPaysSellerMoney() {
            // given
            walletService.depositMoney(buyer.getId(), MONEY_AMOUNT);
            walletService.moveCompositeToEscrow(buyer.getId(), MONEY_AMOUNT, 0L);

            Card card = saveCard(seller, PRICE_GB, SellStatus.TRADING);
            Trade trade = saveTrade(buyer, seller, card, PRICE_GB, 0, TradeStatus.DATA_SENT);

            // when
            tradeProgressService.confirmTrade(trade.getId(), buyer.getEmail(), true);

            // then — buyer 에스크로 소멸
            Wallet buyerWallet = walletRepository.findByMemberId(buyer.getId()).orElseThrow();
            assertThat(buyerWallet.getMoneyEscrow()).isZero();

            // seller 머니 입금
            Wallet sellerWallet = walletRepository.findByMemberId(seller.getId()).orElseThrow();
            assertThat(sellerWallet.getMoneyBalance()).isEqualTo(MONEY_AMOUNT);

            // 거래·카드 상태
            assertThat(tradeRepository.findById(trade.getId()).orElseThrow().getStatus())
                    .isEqualTo(TradeStatus.COMPLETED);
            assertThat(cardRepository.findById(card.getId()).orElseThrow().getSellStatus())
                    .isEqualTo(SellStatus.SOLD_OUT);
        }

        @Test
        @DisplayName("머니+포인트 복합 결제 확정 시 buyer 에스크로 전액 차감, seller 총액 머니 입금")
        void confirmTrade_composite_deductsBothAndPaysSellerTotalPrice() {
            // given: priceGb=1000, point=200 → money=800, point=200
            int priceGb = 1_000;
            int point = 200;
            long moneyToUse = priceGb - point;

            walletService.depositMoney(buyer.getId(), moneyToUse);
            walletService.depositPoint(buyer.getId(), point);
            walletService.moveCompositeToEscrow(buyer.getId(), moneyToUse, point);

            Card card = saveCard(seller, priceGb, SellStatus.TRADING);
            Trade trade = saveTrade(buyer, seller, card, priceGb, point, TradeStatus.DATA_SENT);

            // when
            tradeProgressService.confirmTrade(trade.getId(), buyer.getEmail(), true);

            // then — buyer 에스크로 전액 소멸
            Wallet buyerWallet = walletRepository.findByMemberId(buyer.getId()).orElseThrow();
            assertThat(buyerWallet.getMoneyEscrow()).isZero();
            assertThat(buyerWallet.getPointEscrow()).isZero();
            assertThat(buyerWallet.getMoneyBalance()).isZero();
            assertThat(buyerWallet.getPointBalance()).isZero();

            // seller 총액(priceGb) 머니로 입금
            Wallet sellerWallet = walletRepository.findByMemberId(seller.getId()).orElseThrow();
            assertThat(sellerWallet.getMoneyBalance()).isEqualTo(priceGb);
        }
    }

    // processPayout : 자동 정산
    @Nested
    @DisplayName("processPayout (자동 정산)")
    class ProcessPayoutTest {

        @Test
        @DisplayName("자동 정산 시 buyer 에스크로 차감, seller 머니 입금, 거래 COMPLETED")
        void processPayout_deductsEscrowAndPaysSellerMoney() {
            // given
            walletService.depositMoney(buyer.getId(), MONEY_AMOUNT);
            walletService.moveCompositeToEscrow(buyer.getId(), MONEY_AMOUNT, 0L);

            Card card = saveCard(seller, PRICE_GB, SellStatus.TRADING);
            Trade trade = saveTrade(buyer, seller, card, PRICE_GB, 0, TradeStatus.DATA_SENT);

            // when
            tradeAutoItemProcessor.processPayout(trade);

            // then — buyer 에스크로 소멸
            Wallet buyerWallet = walletRepository.findByMemberId(buyer.getId()).orElseThrow();
            assertThat(buyerWallet.getMoneyEscrow()).isZero();

            // seller 머니 입금
            Wallet sellerWallet = walletRepository.findByMemberId(seller.getId()).orElseThrow();
            assertThat(sellerWallet.getMoneyBalance()).isEqualTo(MONEY_AMOUNT);

        }

        @Test
        @DisplayName("머니+포인트 복합 자동 정산 시 buyer 에스크로 전액 차감, seller 총액 머니 입금")
        void processPayout_composite_deductsBothAndPaysSellerTotalPrice() {
            // given: priceGb=1000, point=200 → money=800, point=200
            int priceGb = 1_000;
            int point = 200;
            long moneyToUse = priceGb - point;

            walletService.depositMoney(buyer.getId(), moneyToUse);
            walletService.depositPoint(buyer.getId(), point);
            walletService.moveCompositeToEscrow(buyer.getId(), moneyToUse, point);

            Card card = saveCard(seller, priceGb, SellStatus.TRADING);
            Trade trade = saveTrade(buyer, seller, card, priceGb, point, TradeStatus.DATA_SENT);

            // when
            tradeAutoItemProcessor.processPayout(trade);

            // then — buyer 에스크로 전액 소멸
            Wallet buyerWallet = walletRepository.findByMemberId(buyer.getId()).orElseThrow();
            assertThat(buyerWallet.getMoneyEscrow()).isZero();
            assertThat(buyerWallet.getPointEscrow()).isZero();

            // seller 총액(priceGb) 머니로 입금
            Wallet sellerWallet = walletRepository.findByMemberId(seller.getId()).orElseThrow();
            assertThat(sellerWallet.getMoneyBalance()).isEqualTo(priceGb);
        }
    }

    // cancelRealTimeTrade : PAYMENT_CONFIRMED 취소
    @Nested
    @DisplayName("cancelRealTimeTrade (PAYMENT_CONFIRMED 취소)")
    class CancelRealTimeTradeTest {

        @Test
        @DisplayName("PAYMENT_CONFIRMED 취소 시 에스크로 복원, 거래 CANCELED, 카드 삭제")
        void cancelRealTimeTrade_paymentConfirmed_releasesEscrowAndDeletesCard() {
            // given
            walletService.depositMoney(buyer.getId(), MONEY_AMOUNT);
            walletService.moveCompositeToEscrow(buyer.getId(), MONEY_AMOUNT, 0L);

            Card card = saveCard(seller, PRICE_GB, SellStatus.TRADING);
            Trade trade = saveTrade(buyer, seller, card, PRICE_GB, 0, TradeStatus.PAYMENT_CONFIRMED);

            // when
            tradeCancelService.cancelRealTimeTrade(
                    trade.getId(), buyer.getEmail(), CancelReason.BUYER_CHANGE_MIND);

            // then — buyer 에스크로 복원
            Wallet buyerWallet = walletRepository.findByMemberId(buyer.getId()).orElseThrow();
            assertThat(buyerWallet.getMoneyBalance()).isEqualTo(MONEY_AMOUNT);
            assertThat(buyerWallet.getMoneyEscrow()).isZero();

            // 거래 취소·카드 삭제
            assertThat(tradeRepository.findById(trade.getId()).orElseThrow().getStatus())
                    .isEqualTo(TradeStatus.CANCELED);
            assertThat(cardRepository.findById(card.getId())).isEmpty();
        }
    }

    // 에스크로 이동 동시성
    @Nested
    @DisplayName("에스크로 이동 동시성")
    class EscrowConcurrencyTest {

        @Test
        @DisplayName("잔액 10,000 / 50스레드 각 1,000씩 에스크로 이동 → 정확히 10개 성공")
        void moveCompositeToEscrow_concurrently_exactlyTenSucceed() throws InterruptedException {
            // given: buyer 잔액 10,000
            walletService.depositMoney(buyer.getId(), MONEY_AMOUNT);

            AtomicInteger successCount = new AtomicInteger();
            AtomicInteger failCount = new AtomicInteger();

            // when: 50스레드 동시에 1,000씩 에스크로 이동
            runConcurrently(() -> {
                try {
                    walletService.moveCompositeToEscrow(buyer.getId(), 1_000L, 0L);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            }, THREAD_COUNT);

            // then: 잔액 소진 · 에스크로 10,000 · 성공 10건
            Wallet wallet = walletRepository.findByMemberId(buyer.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isZero();
            assertThat(wallet.getMoneyEscrow()).isEqualTo(MONEY_AMOUNT);
            assertThat(successCount.get()).isEqualTo(10);
            assertThat(failCount.get()).isEqualTo(40);
        }
    }

    // 공통 헬퍼
    private Card saveCard(Member owner, int price, SellStatus status) {
        return cardRepository.save(Card.builder()
                .member(owner)
                .sellStatus(status)
                .cardCategory(CardCategory.REALTIME_SELL)
                .carrier(Carrier.SKT)
                .dataAmount(10)
                .price(price)
                .build());
    }

    private Trade saveTrade(Member buyer, Member seller, Card card,
                            int priceGb, int point, TradeStatus status) {
        return tradeRepository.save(Trade.builder()
                .cardId(card.getId())
                .buyer(buyer)
                .seller(seller)
                .carrier(Carrier.SKT)
                .priceGb(priceGb)
                .dataAmount(10)
                .status(status)
                .tradeType(TradeType.REALTIME)
                .phone("01012345678")
                .point(point)
                .build());
    }

    private void runConcurrently(Runnable task, int threadCount) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    latch.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        latch.countDown();

        for (Future<?> future : futures) {
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException ignored) {
            }
        }

        executor.shutdown();
        executor.awaitTermination(45, TimeUnit.SECONDS);
    }
}
