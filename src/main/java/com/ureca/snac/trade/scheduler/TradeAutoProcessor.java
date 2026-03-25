package com.ureca.snac.trade.scheduler;

import com.ureca.snac.member.Activated;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.trade.entity.Trade;
import com.ureca.snac.trade.entity.TradeStatus;
import com.ureca.snac.trade.repository.TradeRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Component
@Profile("!loadtest")
@RequiredArgsConstructor
public class TradeAutoProcessor {

    private final TradeRepository tradeRepo;
    private final MemberRepository memberRepository;
    private final TradeAutoItemProcessor tradeAutoItemProcessor;

    /**
     * 판매자가 48 시간 내 전송 안 한 거래 자동 환불
     * 각 건은 TradeAutoItemProcessor에 위임 → 단건 독립 트랜잭션 + 재시도 보장
     */
    @Scheduled(cron = "0 0 * * * *")       // 매 정시
    public void refundIfSellerNoSend() {
        LocalDateTime limit = LocalDateTime.now().minus(48, ChronoUnit.HOURS);

        List<Trade> trades = tradeRepo
                .findByStatusAndUpdatedAtBefore(TradeStatus.PAYMENT_CONFIRMED, limit);

        trades.forEach(trade -> {
            try {
                tradeAutoItemProcessor.processRefund(trade);
            } catch (Exception e) {
                log.error("[자동 환불 처리 실패, 다음 건 계속] tradeId: {}", trade.getId(), e);
            }
        });
    }

    /**
     * 구매자가 48 시간 내 확정 안 한 거래 자동 정산
     * 각 건은 TradeAutoItemProcessor에 위임 → 단건 독립 트랜잭션 + 재시도 보장
     */
    @Scheduled(cron = "0 30 * * * *")      // 매시 30분
    public void payoutIfBuyerNoConfirm() {
        LocalDateTime limit = LocalDateTime.now().minus(48, ChronoUnit.HOURS);

        List<Trade> trades = tradeRepo
                .findByStatusAndUpdatedAtBeforeAndAutoConfirmPausedFalse(TradeStatus.DATA_SENT, limit);

        trades.forEach(trade -> {
            try {
                tradeAutoItemProcessor.processPayout(trade);
            } catch (Exception e) {
                log.error("[자동 정산 처리 실패, 다음 건 계속] tradeId: {}", trade.getId(), e);
            }
        });
    }

    @Scheduled(cron = "0 0 0 * * *")  // 매일 00:00
    @Transactional
    public void liftExpiredSuspensions() {
        List<Member> members = memberRepository.findByActivatedAndSuspendUntilBefore(
                Activated.TEMP_SUSPEND, LocalDateTime.now());
        members.forEach(Member::activate);
    }
}
