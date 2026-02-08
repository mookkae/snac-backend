package com.ureca.snac.payment.service;

import com.ureca.snac.common.notification.SlackNotifier;
import com.ureca.snac.common.notification.dto.SlackAttachment;
import com.ureca.snac.common.notification.dto.SlackField;
import com.ureca.snac.common.notification.dto.SlackMessage;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 결제 관련 알림 서비스
 * <p>
 * 결제 처리 중 발생하는 치명적인 상황에 대한 Slack 알림 담당
 * MoneyServiceImpl에서 알림 책임 분리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentAlertService {

    private final SlackNotifier slackNotifier;

    // Auto-Cancel 실패 시 운영자 알림
    public void alertAutoCancelFailure(AutoCancelFailureEvent event) {
        List<SlackField> fields = List.of(
                SlackField.of("Payment ID", String.valueOf(event.paymentId())),
                SlackField.of("Payment Key", event.paymentKey()),
                SlackField.of("Order ID", event.orderId()),
                SlackField.of("Amount", event.amount() + "원"),
                SlackField.longField("DB Error", event.dbErrorMessage()),
                SlackField.longField("Cancel Error", event.cancelErrorMessage()),
                SlackField.longField("조치",
                        """
                                1. Toss 관리자 콘솔에서 결제 상태 확인
                                2. 수동 취소 또는 DB 동기화 결정
                                3. Payment ID로 DB 상태 확인
                                """)
        );
        SlackAttachment attachment = SlackAttachment.danger(fields);
        SlackMessage message = SlackMessage.of("CRITICAL: 결제 자동취소 실패", attachment);

        log.error("[CRITICAL] Auto-Cancel 실패! paymentKey: {}, orderId: {}",
                event.paymentKey(), event.orderId());
        slackNotifier.sendAsync(message);
    }

    /**
     * Toss 취소 성공 후 DB 보상 처리마저 실패한 Critical 상황 잔액 불일치 발생 가능
     * 결제 취소 보상 처리 실패 시 운영자 알림
     */
    public void alertCompensationFailure(CompensationFailureEvent event) {
        List<SlackField> fields = List.of(
                SlackField.of("Payment ID", String.valueOf(event.paymentId())),
                SlackField.of("Member ID", String.valueOf(event.memberId())),
                SlackField.of("Payment Key", event.paymentKey()),
                SlackField.of("Order ID", event.orderId()),
                SlackField.of("Amount", event.amount() + "원"),
                SlackField.of("Cancel Reason", event.cancelReason()),
                SlackField.of("Canceled At", event.canceledAt().toString()),
                SlackField.longField("Original Error", event.originalErrorMessage()),
                SlackField.longField("Compensation Error", event.compensationErrorMessage()),
                SlackField.longField("조치",
                        """
                                1. Toss 관리자 콘솔에서 취소 상태 확인
                                2. DB에서 Payment 상태 확인 (CANCELED 여부)
                                3. Wallet 잔액 수동 조정 필요
                                4. AssetHistory 기록 수동 추가
                                """)
        );
        SlackAttachment attachment = SlackAttachment.danger(fields);
        SlackMessage message = SlackMessage.of("CRITICAL: 결제 취소 보상 처리 실패", attachment);

        log.error("[CRITICAL] 보상 처리 실패! paymentId: {}, memberId: {}, amount: {}",
                event.paymentId(), event.memberId(), event.amount());
        slackNotifier.sendAsync(message);
    }

    // 대사 스케줄러 자동 취소 성공 시 운영자 알림
    public void alertReconciliationAutoCanceled(Payment payment, String paymentKey) {
        List<SlackField> fields = List.of(
                SlackField.of("Payment ID", String.valueOf(payment.getId())),
                SlackField.of("Payment Key", paymentKey),
                SlackField.of("Order ID", payment.getOrderId()),
                SlackField.of("Amount", payment.getAmount() + "원"),
                SlackField.longField("조치", "자동 취소 완료. 추가 조치 불필요")
        );
        SlackAttachment attachment = SlackAttachment.warning(fields);
        SlackMessage message = SlackMessage.of("대사: 미결 결제 자동 취소 완료", attachment);

        log.warn("[대사] 자동 취소 완료. paymentId: {}, orderId: {}", payment.getId(), payment.getOrderId());
        slackNotifier.sendAsync(message);
    }

    /**
     * 대사 스케줄러 자동 취소 실패 시 운영자 알림
     *
     * @param payment      취소 실패한 결제
     * @param paymentKey   토스 결제 키
     * @param errorMessage 에러 메시지
     */
    public void alertReconciliationCancelFailed(Payment payment, String paymentKey, String errorMessage) {
        List<SlackField> fields = List.of(
                SlackField.of("Payment ID", String.valueOf(payment.getId())),
                SlackField.of("Payment Key", paymentKey),
                SlackField.of("Order ID", payment.getOrderId()),
                SlackField.of("Amount", payment.getAmount() + "원"),
                SlackField.longField("Error", errorMessage),
                SlackField.longField("조치",
                        """
                                1. Toss 관리자 콘솔에서 결제 상태 확인
                                2. 수동 취소 또는 DB 동기화 결정
                                3. Payment ID로 DB 상태 확인
                                """)
        );
        SlackAttachment attachment = SlackAttachment.danger(fields);
        SlackMessage message = SlackMessage.of("CRITICAL: 대사 자동 취소 실패", attachment);

        log.error("[CRITICAL] 대사 자동 취소 실패! paymentId: {}, orderId: {}, error: {}",
                payment.getId(), payment.getOrderId(), errorMessage);
        slackNotifier.sendAsync(message);
    }
}
