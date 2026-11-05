package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.config.RabbitMQQueue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * DlqMonitor가 감시하는 DLQ와 운영자 조치 안내
 * <p>
 * 회원가입, 가입 보너스: 소비자가 다시 받아도 한 번만 반영하므로 원인을 고친 뒤 원래 큐로 전부 되돌린다
 * 결제 취소 보상: 대사 스케줄러가 같은 완결 로직으로 복구하므로 확인 후 삭제한다
 */
@Getter
@RequiredArgsConstructor
public enum MonitoredDlq {

    MEMBER_JOINED(RabbitMQQueue.MEMBER_JOINED_DLQ, "회원가입",
            redrive(RabbitMQQueue.MEMBER_JOINED_DLQ, RabbitMQQueue.MEMBER_JOINED_QUEUE)),

    SIGNUP_BONUS(RabbitMQQueue.WALLET_CREATED_DLQ, "가입 보너스",
            redrive(RabbitMQQueue.WALLET_CREATED_DLQ, RabbitMQQueue.WALLET_CREATED_QUEUE)),

    PAYMENT_CANCEL_COMPENSATE(RabbitMQQueue.PAYMENT_CANCEL_COMPENSATE_DLQ, "결제 취소 보상", """
            1. 대사 스케줄러가 다음 주기에 자동 복구한다
            2. 해당 결제가 CANCELED인지 확인한 뒤 DLQ에서 삭제한다
            3. 급하면 Move messages로 %s에 되돌려도 된다 (다시 처리해도 한 번만 반영)
            """.formatted(RabbitMQQueue.PAYMENT_CANCEL_COMPENSATE_QUEUE));

    private final String queueName;
    private final String domain;
    private final String action;

    private static String redrive(String dlqName, String originalQueue) {
        return """
                1. 로그에서 eventId로 실패 원인을 확인하고 고친다
                2. RabbitMQ Management Console > %s > Move messages로 %s에 되돌린다 (다시 처리해도 한 번만 반영)
                3. 다시 DLQ로 돌아온 건만 원인을 보고 폐기하거나 직접 처리한다 (DLQ TTL이 지나면 사라진다)
                """.formatted(dlqName, originalQueue);
    }
}
