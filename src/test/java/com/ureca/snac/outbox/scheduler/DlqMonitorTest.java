package com.ureca.snac.outbox.scheduler;

import com.ureca.snac.common.notification.SlackNotifier;
import com.ureca.snac.common.notification.dto.SlackMessage;
import com.ureca.snac.config.RabbitMQQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * DlqMonitor 단위 테스트
 * <p>
 * 검증 목표:
 * - 큐 개수 조회 로직
 * - 중복 알림 방지 (개수 변화 감지)
 * - SlackMessage 생성 로직
 * - 알림 트리거 조건
 * <p>
 * 검증 제외:
 * - @Scheduled (Spring의 책임)
 */
@ExtendWith(MockitoExtension.class)
class DlqMonitorTest {

    @InjectMocks
    private DlqMonitor dlqMonitor;

    @Mock
    private AmqpAdmin amqpAdmin;

    @Mock
    private SlackNotifier slackNotifier;

    @Test
    @DisplayName("정상 : DLQ 메시지 -> 알림 전송")
    void monitorDlq_HasMessages_SendAlert() {
        // given
        QueueInformation queueInfo = mock(QueueInformation.class);
        given(queueInfo.getMessageCount()).willReturn(5);
        given(amqpAdmin.getQueueInfo(RabbitMQQueue.MEMBER_JOINED_DLQ))
                .willReturn(queueInfo);

        // when
        dlqMonitor.monitorDlq();

        // then 알림 전송 확인
        ArgumentCaptor<SlackMessage> captor = ArgumentCaptor.forClass(SlackMessage.class);
        verify(slackNotifier, times(1)).sendAsync(captor.capture());

        SlackMessage message = captor.getValue();
        assertThat(message.text()).contains("DLQ 메시지 감지");
        assertThat(message.attachments()).isNotEmpty();
    }

    @Test
    @DisplayName("중복 방지 : 개수 동일 -> 알림 생략")
    void monitorDlq_SameCount_NoAlert() {
        // given
        QueueInformation queueInfo = mock(QueueInformation.class);
        given(queueInfo.getMessageCount()).willReturn(5);
        given(amqpAdmin.getQueueInfo(RabbitMQQueue.MEMBER_JOINED_DLQ))
                .willReturn(queueInfo);

        // 첫 번째 알림 전송
        dlqMonitor.monitorDlq();
        verify(slackNotifier, times(1)).sendAsync(any());

        // when 두 번째 개수 동일
        dlqMonitor.monitorDlq();

        // then 알림 전송 안 함
        verify(slackNotifier, times(1)).sendAsync(any());
    }

    @Test
    @DisplayName("개수 증가 : 알림 재전송")
    void monitorDlq_CountIncreased_SendAlert() {
        // given
        QueueInformation queueInfo1 = mock(QueueInformation.class);
        given(queueInfo1.getMessageCount()).willReturn(5);

        QueueInformation queueInfo2 = mock(QueueInformation.class);
        given(queueInfo2.getMessageCount()).willReturn(10);

        given(amqpAdmin.getQueueInfo(RabbitMQQueue.MEMBER_JOINED_DLQ))
                .willReturn(queueInfo1)  // 첫 번째 5건
                .willReturn(queueInfo2); // 두 번째 10건

        // when
        dlqMonitor.monitorDlq();  // 첫 번째 : 5건 -> 알림
        dlqMonitor.monitorDlq();  // 두 번째 : 10건 -> 알림

        // then 총 2번 알림
        verify(slackNotifier, times(2)).sendAsync(any());
    }

    @Test
    @DisplayName("메시지 0건 : 캐시 제거")
    void monitorDlq_ZeroMessages_ClearCache() {
        // given
        QueueInformation queueInfo1 = mock(QueueInformation.class);
        given(queueInfo1.getMessageCount()).willReturn(5);

        QueueInformation queueInfo2 = mock(QueueInformation.class);
        given(queueInfo2.getMessageCount()).willReturn(0);

        given(amqpAdmin.getQueueInfo(RabbitMQQueue.MEMBER_JOINED_DLQ))
                .willReturn(queueInfo1)  // 첫 번째 5건
                .willReturn(queueInfo2); // 두 번째 0건

        // when
        dlqMonitor.monitorDlq();  // 첫 번째 알림
        dlqMonitor.monitorDlq();  // 두 번째 캐시 제거

        // then 알림 1번만
        verify(slackNotifier, times(1)).sendAsync(any());

        // 다시 메시지 발생 시 알림 가능
        QueueInformation queueInfo3 = mock(QueueInformation.class);
        given(queueInfo3.getMessageCount()).willReturn(3);
        given(amqpAdmin.getQueueInfo(RabbitMQQueue.MEMBER_JOINED_DLQ))
                .willReturn(queueInfo3);

        dlqMonitor.monitorDlq();  // 세 번째: 3건 -> 알림
        verify(slackNotifier, times(2)).sendAsync(any());
    }

    @Test
    @DisplayName("예외 : 큐 조회 실패 -> 알림 안 함")
    void monitorDlq_QueueFindFailed_NoAlert() {
        // given
        given(amqpAdmin.getQueueInfo(RabbitMQQueue.MEMBER_JOINED_DLQ))
                .willThrow(new RuntimeException("RabbitMQ 연결 실패"));

        // when
        dlqMonitor.monitorDlq();

        // then 예외 무시, 알림 안 함
        verify(slackNotifier, never()).sendAsync(any());
    }
}