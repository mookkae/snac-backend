package com.ureca.snac.wallet.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.wallet.event.WalletCreatedEvent;
import com.ureca.snac.wallet.exception.WalletNotFoundException;
import com.ureca.snac.wallet.service.SignupBonusService;
import com.ureca.snac.common.metric.TransactionAwareMetricRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 지갑 생성 완료 후 회원가입 축하 포인트 지급 리스너
 * 재시도 불가능한 에러는 즉시 DLQ 격리
 * 비즈니스 로직은 SignupBonusService에 위임하여 분리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignupBonusListener {

    private final SignupBonusService signupBonusService;
    private final ObjectMapper objectMapper;
    private final TransactionAwareMetricRecorder metricRecorder;

    // 지갑 생성 이벤트 처리
    @RabbitListener(queues = RabbitMQQueue.WALLET_CREATED_QUEUE)
    public void handleWalletCreatedEvent(String payload) {
        String result = "success";
        Long memberId = null;

        try {
            log.info("[포인트 지급 리스너] 지갑 생성 이벤트 수신. payload: {}", payload);

            // 1. JSON 역직렬화
            WalletCreatedEvent event = deserializeEvent(payload);
            memberId = event.memberId();

            log.info("[포인트 지급 리스너] 처리 시작. 회원 ID: {}, 지갑 ID: {}",
                    memberId, event.walletId());

            // 2. 포인트 지급 (SignupBonusService에 위임)
            signupBonusService.grantSignupBonus(memberId);

            log.info("[포인트 지급 리스너] 완료. 회원 ID: {}", memberId);

        } catch (JsonProcessingException e) {
            result = "dlq";
            // 재시도 안 하는 예외 (JSON 파싱 실패)
            log.error("[포인트 지급 리스너] JSON 파싱 실패. 즉시 DLQ 이동. payload: {}", payload, e);
            throw new AmqpRejectAndDontRequeueException("JSON 파싱 불가", e);

        } catch (WalletNotFoundException e) {
            result = "dlq";
            // WalletCreatedEvent는 지갑 커밋 후 발행되므로, 여기서 지갑이 없으면 데이터 정합성 문제
            log.error("[포인트 지급 리스너] 지갑 없음. 데이터 정합성 문제. 즉시 DLQ 이동. 회원 ID: {}", memberId, e);
            throw new AmqpRejectAndDontRequeueException("지갑 없음: " + memberId, e);

        } catch (DataIntegrityViolationException e) {
            result = "duplicate";
            // 동시성으로 인한 중복 지급
            log.warn("[포인트 지급 리스너] 이미 포인트 지급 (동시성) 중복 지급 방지. 회원 ID : {}, payload : {}", memberId, payload);
            // ACK 정상 처리 한다고 보냄

        } catch (Exception e) {
            result = "fail";
            // JSON 파싱/지갑 없음/중복 외 예외는 일시적 장애로 보고 재시도
            log.error("[포인트 지급 리스너] 일시적 장애 발생. 재시도 예정. 회원 ID: {}", memberId, e);
            throw e;

        } finally {
            metricRecorder.increment("listener_message_processed_total",
                    "queue", RabbitMQQueue.WALLET_CREATED_QUEUE,
                    "result", result);
        }
    }

    private WalletCreatedEvent deserializeEvent(String payload) throws JsonProcessingException {
        return objectMapper.readValue(payload, WalletCreatedEvent.class);
    }
}