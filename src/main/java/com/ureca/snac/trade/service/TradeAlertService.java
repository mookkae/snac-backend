package com.ureca.snac.trade.service;

import com.ureca.snac.common.notification.SlackNotifier;
import com.ureca.snac.common.notification.dto.SlackAttachment;
import com.ureca.snac.common.notification.dto.SlackField;
import com.ureca.snac.common.notification.dto.SlackMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 거래 관련 장애 알림 서비스
 *
 * <p>TradeInitiationServiceImpl, TradeProgressServiceImpl, TradeAutoItemProcessor의
 * @Recover 메서드에서 알림 책임을 분리합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeAlertService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SlackNotifier slackNotifier;

    public void alertPayRealTimeTradeFailure(Long tradeId, String username, DataAccessException e) {
        log.error("[실시간 결제 최종 실패] 재시도 모두 소진. tradeId: {}, username: {}", tradeId, username, e);
        slackNotifier.sendAsync(SlackMessage.of("⚠️ Trade 실시간 결제 DB 처리 최종 실패",
                SlackAttachment.danger(List.of(
                        SlackField.of("거래 ID", String.valueOf(tradeId)),
                        SlackField.of("요청자", username),
                        SlackField.of("발생 시각", now()),
                        SlackField.longField("오류", e.getMessage()),
                        SlackField.longField("조치",
                                """
                                        1. Trade DB 상태 확인 (tradeId로 Trade 테이블 조회)
                                        2. Wallet 잔액 확인 (실제 차감 여부)
                                        3. 수동 결제 처리 또는 거래 취소 결정
                                        """)
                ))));
    }

    public void alertCreateTradeFailure(Long cardId, String username, DataAccessException e) {
        log.error("[거래 생성 최종 실패] 재시도 모두 소진. cardId: {}, username: {}", cardId, username, e);
        slackNotifier.sendAsync(SlackMessage.of("⚠️ Trade 거래 생성 DB 처리 최종 실패",
                SlackAttachment.danger(List.of(
                        SlackField.of("카드 ID", String.valueOf(cardId)),
                        SlackField.of("요청자", username),
                        SlackField.of("발생 시각", now()),
                        SlackField.longField("오류", e.getMessage()),
                        SlackField.longField("조치",
                                """
                                        1. Card DB 상태 확인 (cardId로 Card 테이블 조회)
                                        2. Wallet 잔액 확인 (실제 차감 여부)
                                        3. 수동 거래 생성 또는 환불 결정
                                        """)
                ))));
    }

    public void alertCreateRealTimeTradeFailure(Long cardId, String username, DataAccessException e) {
        log.error("[실시간 거래 생성 최종 실패] 재시도 모두 소진. cardId: {}, username: {}", cardId, username, e);
        slackNotifier.sendAsync(SlackMessage.of("⚠️ 실시간 거래 생성 DB 처리 최종 실패",
                SlackAttachment.danger(List.of(
                        SlackField.of("카드 ID", String.valueOf(cardId)),
                        SlackField.of("요청자", username),
                        SlackField.of("발생 시각", now()),
                        SlackField.longField("오류", e.getMessage()),
                        SlackField.longField("조치",
                                """
                                        1. Card DB 상태 확인 (cardId로 Card 테이블 조회)
                                        2. Trade 생성 여부 확인
                                        3. 수동 처리 결정
                                        """)
                ))));
    }

    public void alertConfirmTradeFailure(Long tradeId, String username, DataAccessException e) {
        log.error("[거래 확정 최종 실패] 재시도 모두 소진. tradeId: {}, username: {}", tradeId, username, e);
        slackNotifier.sendAsync(SlackMessage.of("⚠️ 거래 확정 DB 처리 최종 실패",
                SlackAttachment.danger(List.of(
                        SlackField.of("거래 ID", String.valueOf(tradeId)),
                        SlackField.of("요청자", username),
                        SlackField.of("발생 시각", now()),
                        SlackField.longField("오류", e.getMessage()),
                        SlackField.longField("조치",
                                """
                                        1. Trade DB 상태 확인 (tradeId로 Trade 테이블 조회)
                                        2. 판매자 Wallet 잔액 확인 (정산 반영 여부)
                                        3. 수동 정산 처리 결정
                                        """)
                ))));
    }

    public void alertAutoRefundFailure(Long tradeId, DataAccessException e) {
        log.error("[자동 환불 최종 실패] 재시도 모두 소진. tradeId: {}", tradeId, e);
        slackNotifier.sendAsync(SlackMessage.of("⚠️ 자동 환불 DB 처리 최종 실패",
                SlackAttachment.danger(List.of(
                        SlackField.of("거래 ID", String.valueOf(tradeId)),
                        SlackField.of("발생 시각", now()),
                        SlackField.longField("오류", e.getMessage()),
                        SlackField.longField("조치",
                                """
                                        1. Trade DB 상태 확인 (tradeId로 Trade 테이블 조회)
                                        2. 구매자 Wallet 잔액 확인 (환불 반영 여부)
                                        3. 수동 환불 처리 결정
                                        """)
                ))));
    }

    public void alertAutoPayoutFailure(Long tradeId, DataAccessException e) {
        log.error("[자동 정산 최종 실패] 재시도 모두 소진. tradeId: {}", tradeId, e);
        slackNotifier.sendAsync(SlackMessage.of("⚠️ 자동 정산 DB 처리 최종 실패",
                SlackAttachment.danger(List.of(
                        SlackField.of("거래 ID", String.valueOf(tradeId)),
                        SlackField.of("발생 시각", now()),
                        SlackField.longField("오류", e.getMessage()),
                        SlackField.longField("조치",
                                """
                                        1. Trade DB 상태 확인 (tradeId로 Trade 테이블 조회)
                                        2. 판매자 Wallet 잔액 확인 (정산 반영 여부)
                                        3. 수동 정산 처리 결정
                                        """)
                ))));
    }

    private String now() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }
}
