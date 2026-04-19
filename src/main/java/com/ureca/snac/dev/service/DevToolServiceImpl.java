package com.ureca.snac.dev.service;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.AssetType;
import com.ureca.snac.asset.repository.AssetHistoryRepository;
import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.dev.dto.DevCancelRechargeRequest;
import com.ureca.snac.dev.dto.DevForceTradeCompleteRequest;
import com.ureca.snac.dev.dto.DevPointGrantRequest;
import com.ureca.snac.dev.dto.DevRechargeRequest;
import com.ureca.snac.dev.support.DevDataSupport;
import com.ureca.snac.payment.port.out.PaymentGatewayPort;
import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.money.service.MoneyDepositor;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.payment.service.PaymentService;
import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class DevToolServiceImpl implements DevToolService {

    private final MemberRepository memberRepository;
    private final PaymentService paymentService;
    private final MoneyDepositor moneyDepositor;
    private final DevDataSupport devDataSupport;
    private final WalletService walletService;
    private final PaymentRepository paymentRepository;
    private final AssetRecorder assetRecorder;
    private final AssetHistoryRepository assetHistoryRepository;
    private final PaymentGatewayPort paymentGatewayPort;

    public DevToolServiceImpl(
            MemberRepository memberRepository,
            PaymentService paymentService,
            MoneyDepositor moneyDepositor,
            DevDataSupport devDataSupport,
            WalletService walletService,
            PaymentRepository paymentRepository,
            AssetRecorder assetRecorder,
            AssetHistoryRepository assetHistoryRepository,
            @Qualifier("fake")
            PaymentGatewayPort paymentGatewayPort) {

        this.memberRepository = memberRepository;
        this.paymentService = paymentService;
        this.moneyDepositor = moneyDepositor;
        this.devDataSupport = devDataSupport;
        this.walletService = walletService;
        this.paymentRepository = paymentRepository;
        this.assetRecorder = assetRecorder;
        this.assetHistoryRepository = assetHistoryRepository;
        this.paymentGatewayPort = paymentGatewayPort;
    }

    @Override
    @Transactional
    public Long forceRecharge(DevRechargeRequest request) {
        log.info("[개발용 머니 충전] 시작. 이메일 : {}, 금액 : {}",
                request.email(), request.amount());

        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(MemberNotFoundException::new);

        Payment payment = paymentService.preparePayment(member, request.amount());

        PaymentConfirmResult fakeConfirmResponse =
                paymentGatewayPort.confirmPayment(
                        "dev_payment_key_" + System.currentTimeMillis(),
                        payment.getOrderId(),
                        payment.getAmount()
                );

        moneyDepositor.deposit(payment.getId(), member.getId(), fakeConfirmResponse);

        log.info("[개발용 머니 충전] 완료. 생성된 Payment Id : {}", payment.getId());
        return payment.getId();
    }

    @Override
    @Transactional
    public void grantPoint(DevPointGrantRequest request) {
        log.info("[개발용 포인트 지급] 시작. 이메일 : {}, 양 : {}, 이유 : {}",
                request.email(), request.amount(), request.reason());

        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(MemberNotFoundException::new);

        long balanceAfter = walletService.depositPoint(member.getId(), request.amount());

        // 개발용 포인트 지급은 커스텀 사유를 허용하므로 직접 저장
        saveDevPointGrant(member, request.amount(), balanceAfter, request.reason());

        log.info("[개발용 포인트 지급] 완료.");
    }

    @Override
    @Transactional
    public void forceCancelRecharge(DevCancelRechargeRequest request) {
        log.info("[개발용 충전 취소] 시작. Payment ID : {}, 사유 : {}",
                request.paymentId(), request.reason());

        Payment payment = paymentRepository.findById(request.paymentId())
                .orElseThrow(PaymentNotFoundException::new);

        paymentService.cancelPayment(
                payment.getPaymentKey(),
                request.reason(),
                payment.getMember().getEmail()
        );

        log.info("[개발용 충전 취소] 취소 서비스 완료.");
    }


    @Override
    @Transactional
    public Long forceTradeComplete(DevForceTradeCompleteRequest request) {
        log.info("[개발용 거래 완료] 시작.");

        DevDataSupport.TradeCompletionContext ctx =
                devDataSupport.prepareCompletedTrade(request.cardOwnerEmail(), request.counterEmail(),
                        request.cardCategory(), request.carrier(), request.dataAmount(),
                        request.moneyAmountToUse(), request.pointAmountToUse()
                );

        walletService.moveCompositeToEscrow(ctx.buyer().getId(), request.moneyAmountToUse(), request.pointAmountToUse());
        CompositeBalanceResult buyerResult = walletService.deductCompositeEscrow(ctx.buyer().getId(), request.moneyAmountToUse(), request.pointAmountToUse());

        long sellerMoneyBalanceAfter = (request.moneyAmountToUse() > 0) ?
                walletService.depositMoney(ctx.seller().getId(), request.moneyAmountToUse()) :
                walletService.getMoneyBalance(ctx.seller().getId());

        recordTradeAssets(ctx, request.moneyAmountToUse(), request.pointAmountToUse(), sellerMoneyBalanceAfter, buyerResult);

        log.info("[개발용 거래 완료] 완료. 생성된 Trade ID : {}", ctx.trade().getId());

        return ctx.trade().getId();
    }

    private void recordTradeAssets(DevDataSupport.TradeCompletionContext ctx, long moneyUsed,
                                   long pointUsed, long sellerMoneyBalance, CompositeBalanceResult buyerResult) {

        String title = String.format("%s %dGB", ctx.card().getCarrier().name(), ctx.card().getDataAmount());

        if (moneyUsed > 0) {
            assetRecorder.recordTradeBuy(ctx.buyer().getId(), ctx.trade().getId(),
                    title + " 머니 사용", AssetType.MONEY, moneyUsed, buyerResult.moneyBalance());
            assetRecorder.recordTradeSell(ctx.seller().getId(), ctx.trade().getId(),
                    title + " 판매 대금", moneyUsed, sellerMoneyBalance);
        }
        if (pointUsed > 0) {
            assetRecorder.recordTradeBuy(ctx.buyer().getId(), ctx.trade().getId(),
                    title + " 포인트 사용", AssetType.POINT, pointUsed, buyerResult.pointBalance());
        }
    }

    private void saveDevPointGrant(Member member, Long amount, Long balanceAfter, String reason) {
        Long grantId = System.nanoTime();  // 고유 ID 생성 (개발용)
        AssetHistory history = AssetHistory.createAdminPointGrant(member, grantId, amount, balanceAfter, reason);
        assetHistoryRepository.save(history);
    }
}
