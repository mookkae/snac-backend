package com.ureca.snac.payment.service;

import com.ureca.snac.common.notification.SlackNotifier;
import com.ureca.snac.common.notification.dto.SlackAttachment;
import com.ureca.snac.common.notification.dto.SlackField;
import com.ureca.snac.common.notification.dto.SlackMessage;
import com.ureca.snac.payment.event.alert.AutoCancelFailureEvent;
import com.ureca.snac.payment.event.alert.CompensationFailureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 결제 관련 알림 컴포넌트
 * <p>
 * 결제 처리 중 발생하는 치명적인 상황에 대한 Slack 알림 담당
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentAlertNotifier {

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
                                대사 스케줄러가 자동 복구를 시도합니다.
                                스케줄러 복구 실패 알림 수신 시:
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

    // Toss 취소 성공 후 DB 보상 처리마저 실패한 Critical, 잔액 불일치 발생 가능
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
    public void alertReconciliationAutoCanceled(Long paymentId, String orderId, Long amount, String paymentKey) {
        List<SlackField> fields = List.of(
                SlackField.of("Payment ID", String.valueOf(paymentId)),
                SlackField.of("Payment Key", paymentKey),
                SlackField.of("Order ID", orderId),
                SlackField.of("Amount", amount + "원"),
                SlackField.longField("조치", "자동 취소 완료. 추가 조치 불필요")
        );
        SlackAttachment attachment = SlackAttachment.warning(fields);
        SlackMessage message = SlackMessage.of("대사: 미결 결제 자동 취소 완료", attachment);

        log.warn("[대사] 자동 취소 완료. paymentId: {}, orderId: {}", paymentId, orderId);
        slackNotifier.sendAsync(message);
    }

    // Toss NOT_CANCELABLE_PAYMENT 확정 시 운영자 알림, frozen 해제 + Payment SUCCESS 복구 완료 후 호출
    public void alertCancellationRejectedByGateway(Long paymentId, String orderId, Long amount,
                                                String paymentKey, String source) {
        List<SlackField> fields = List.of(
                SlackField.of("Payment ID", String.valueOf(paymentId)),
                SlackField.of("Payment Key", paymentKey),
                SlackField.of("Order ID", orderId),
                SlackField.of("Amount", amount + "원"),
                SlackField.of("발생 경로", source),
                SlackField.longField("처리 결과", "frozen 해제 + Payment SUCCESS 복구 완료"),
                SlackField.longField("조치",
                        """
                                1. Toss 관리자 콘솔에서 NOT_CANCELABLE 사유 확인
                                2. 사용자에게 취소 불가 안내 필요
                                3. 필요 시 수동 환불 절차 검토
                                """)
        );
        SlackAttachment attachment = SlackAttachment.warning(fields);
        SlackMessage message = SlackMessage.of("WARNING: Toss 취소 거절 - frozen 복구 완료", attachment);

        log.warn("[취소 거절] Toss NOT_CANCELABLE. frozen 복구 완료. paymentId: {}, source: {}",
                paymentId, source);
        slackNotifier.sendAsync(message);
    }

    // 대사 스케줄러 자동 취소 실패 시 운영자 알림
    public void alertReconciliationCancelFailed(Long paymentId, String orderId, Long amount,
                                                String paymentKey, String errorMessage) {
        List<SlackField> fields = List.of(
                SlackField.of("Payment ID", String.valueOf(paymentId)),
                SlackField.of("Payment Key", paymentKey),
                SlackField.of("Order ID", orderId),
                SlackField.of("Amount", amount + "원"),
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
                paymentId, orderId, errorMessage);
        slackNotifier.sendAsync(message);
    }
}