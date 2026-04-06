package com.ureca.snac.loadtest;

import com.ureca.snac.common.BaseCode;
import com.ureca.snac.common.exception.BusinessException;
import com.ureca.snac.trade.entity.Trade;
import com.ureca.snac.trade.repository.TradeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E2E 테스트 전용: 트레이드 상태 강제 변경 (seller 검증·S3·SMS 전부 스킵)
 */
@Service
@Profile("loadtest")
@Transactional
@RequiredArgsConstructor
public class LoadTestTradeService {

    private final TradeRepository tradeRepository;

    public void markDataSent(Long tradeId) {
        Trade trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new BusinessException(BaseCode.TRADE_NOT_FOUND));
        trade.markDataSent();
    }
}
