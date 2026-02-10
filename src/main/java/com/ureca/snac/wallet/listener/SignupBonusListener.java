package com.ureca.snac.wallet.listener;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ureca.snac.config.RabbitMQQueue;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.wallet.event.WalletCreatedEvent;
import com.ureca.snac.wallet.service.SignupBonusService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
    private final MeterRegistry meterRegistry;

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

        } catch (MemberNotFoundException e) {
            result = "dlq";
            // 재시도 안 하는 예외 (회원 없음)
            log.error("[포인트 지급 리스너] 회원 없음. 즉시 DLQ 이동. 회원 ID: {}", memberId, e);
            throw new AmqpRejectAndDontRequeueException("회원 없음: " + memberId, e);

        } catch (DataIntegrityViolationException e) {
            // 동시성으로 인한 중복 지급
            log.warn("[포인트 지급 리스너] 이미 포인트 지급 (동시성) 중복 지급 방지. 회원 ID : {}", memberId);
            // ACK 정상 처리 한다고 보냄

        } catch (Exception e) {
            result = "fail";
            // 저거 말고는 일단 재시도하는 예외
            log.error("[포인트 지급 리스너] 일시적 장애 발생. 재시도 예정. 회원 ID: {}", memberId, e);
            throw e;

        } finally {
            Counter.builder("listener_message_processed_total")
                    .tag("queue", RabbitMQQueue.WALLET_CREATED_QUEUE)
                    .tag("result", result)
                    .register(meterRegistry).increment();
        }
    }

    private WalletCreatedEvent deserializeEvent(String payload) throws JsonProcessingException {
        return objectMapper.readValue(payload, WalletCreatedEvent.class);
    }
}