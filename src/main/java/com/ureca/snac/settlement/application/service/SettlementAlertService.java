package com.ureca.snac.settlement.application.service;

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
 * 정산 관련 장애 알림 서비스
 *
 * <p>SettlementServiceImpl의 @Recover 메서드에서 알림 책임을 분리합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementAlertService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SlackNotifier slackNotifier;

    public void alertSettlementFailure(String username, long amount, DataAccessException e) {
        log.error("[정산 최종 실패] 재시도 모두 소진. username: {}, amount: {}", username, amount, e);
        slackNotifier.sendAsync(SlackMessage.of("⚠️ 정산 DB 처리 최종 실패",
                SlackAttachment.danger(List.of(
                        SlackField.of("요청자", username),
                        SlackField.of("금액", String.valueOf(amount)),
                        SlackField.of("발생 시각", now()),
                        SlackField.longField("오류", e.getMessage()),
                        SlackField.longField("조치",
                                """
                                        1. Settlement DB 상태 확인 (username으로 Settlement 테이블 조회)
                                        2. 회원 Wallet 잔액 확인 (출금 반영 여부)
                                        3. 수동 출금 처리 또는 잔액 보정 결정
                                        """)
                ))));
    }

    private String now() {
        return LocalDateTime.now().format(DATE_TIME_FORMATTER);
    }
}
