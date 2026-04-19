package com.ureca.snac.wallet.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.member.event.MemberJoinEvent;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.wallet.service.WalletService;
import com.ureca.snac.common.metric.TransactionAwareMetricRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 회원가입 이벤트 수신 후 지갑 생성
 * 재시도 안하는 예외는 즉시 DLQ 격리
 * JSON 파싱은 트랜잭션 바깥, DB는 서비스에서 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletCreationListener {

    private final WalletService walletService;
    private final ObjectMapper objectMapper;
    private final TransactionAwareMetricRecorder metricRecorder;

    // 회원가입 이벤트 처리
    @RabbitListener(queues = RabbitMQQueue.MEMBER_JOINED_QUEUE)
    public void handleMemberJoinEvent(String payload) {
        String result = "success";
        Long memberId = null;

        try {
            log.info("[지갑 생성] 이벤트 수신. payload: {}", payload);

            // 1. JSON 역직렬화 (트랜잭션 없음)
            MemberJoinEvent event = deserializeEvent(payload);
            memberId = event.memberId();

            log.info("[지갑 생성] 처리 시작. 회원 ID: {}", memberId);

            // 2. 지갑 생성
            walletService.createWallet(memberId);

            log.info("[지갑 생성] 완료. 회원 ID: {}", memberId);

        } catch (JsonProcessingException e) {
            result = "dlq";
            // 재시도 안 하는 예외 (JSON 파싱 실패)
            log.error("[지갑 생성] JSON 파싱 실패. 즉시 DLQ 이동. payload: {}", payload, e);
            throw new AmqpRejectAndDontRequeueException("JSON 파싱 불가", e);

        } catch (MemberNotFoundException e) {
            result = "dlq";
            log.error("[지갑 생성] 회원 없음. 데이터 정합성 문제. 즉시 DLQ 이동. 회원 ID: {}", memberId, e);
            throw new AmqpRejectAndDontRequeueException("회원 없음: " + memberId, e);

        } catch (DataIntegrityViolationException e) {
            result = "duplicate";
            // 동시성으로 인한 중복 생성
            log.warn("[지갑 생성] 이미 존재하는 지갑 (동시성) 중복 생성 방지. 회원 ID : {}", memberId);
            // ACK 정상 처리 한다고 보냄

        } catch (Exception e) {
            result = "fail";
            log.error("[지갑 생성] 일시적 장애 발생. 재시도 예정. 회원 ID: {}", memberId, e);
            throw e;

        } finally {
            metricRecorder.increment("listener_message_processed_total",
                    "queue", RabbitMQQueue.MEMBER_JOINED_QUEUE,
                    "result", result);
        }
    }

    private MemberJoinEvent deserializeEvent(String payload) throws JsonProcessingException {
        return objectMapper.readValue(payload, MemberJoinEvent.class);
    }
}