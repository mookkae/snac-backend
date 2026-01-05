package com.ureca.snac.outbox.entity;

public enum OutboxStatus {
    INIT, // 저장됨 (발행 대기)
    PUBLISHED, // 발행 완료
    SEND_FAIL // 발행 실패 ( 재시도 대상)
}