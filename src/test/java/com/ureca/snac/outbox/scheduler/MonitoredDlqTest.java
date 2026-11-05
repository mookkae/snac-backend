package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.config.RabbitMQQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MonitoredDlqTest {

    // DLQ를 새로 선언하고 감시 대상에 넣지 않으면, 실패 건이 알림 없이 TTL로 사라진다
    @Test
    @DisplayName("감시 대상 : RabbitMQQueue에 선언된 DLQ를 빠짐없이 감시한다")
    void monitorsEveryDeclaredDlq() throws IllegalAccessException {
        // given
        List<String> declaredDlqs = new ArrayList<>();
        for (Field field : RabbitMQQueue.class.getFields()) {
            if (field.getName().endsWith("_DLQ")) {
                declaredDlqs.add((String) field.get(null));
            }
        }

        // when
        List<String> monitored = Arrays.stream(MonitoredDlq.values())
                .map(MonitoredDlq::getQueueName)
                .toList();

        // then
        assertThat(monitored).containsExactlyInAnyOrderElementsOf(declaredDlqs);
    }

    // 지갑 생성과 보너스 지급은 다시 받아도 한 번만 반영되므로, 원인을 고친 뒤 전부 되돌리는 것이 대응이다
    @Test
    @DisplayName("조치 안내 : 회원가입 DLQ는 원래 큐로 되돌리라고 안내한다")
    void memberJoinedGuidesRedriveToOriginalQueue() {
        // when
        String action = MonitoredDlq.MEMBER_JOINED.getAction();

        // then
        assertThat(action).contains("Move messages", RabbitMQQueue.MEMBER_JOINED_QUEUE);
    }

    @Test
    @DisplayName("조치 안내 : 가입 보너스 DLQ는 원래 큐로 되돌리라고 안내한다")
    void signupBonusGuidesRedriveToOriginalQueue() {
        // when
        String action = MonitoredDlq.SIGNUP_BONUS.getAction();

        // then
        assertThat(action).contains("Move messages", RabbitMQQueue.WALLET_CREATED_QUEUE);
    }

    // 결제 취소 보상은 대사 스케줄러가 같은 완결 로직으로 복구하므로, 사람이 할 일은 확인과 삭제다
    @Test
    @DisplayName("조치 안내 : 결제 취소 보상 DLQ는 대사 복구를 확인한 뒤 삭제하라고 안내한다")
    void paymentCancelCompensateGuidesReconciliation() {
        // when
        String action = MonitoredDlq.PAYMENT_CANCEL_COMPENSATE.getAction();

        // then
        assertThat(action).contains("대사", "CANCELED", "삭제");
    }
}
