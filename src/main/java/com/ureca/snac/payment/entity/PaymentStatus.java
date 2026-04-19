package com.ureca.snac.payment.entity;

public enum PaymentStatus {
    /** 결제 대기 중 — Payment 생성 직후, 토스 승인 전 */
    PENDING {
        @Override
        public boolean canTransitionTo(PaymentStatus next) {
            return next == SUCCESS || next == CANCELED;
        }
    },
    /** 결제 성공 — 토스 승인 완료 + DB 반영 */
    SUCCESS {
        @Override
        public boolean canTransitionTo(PaymentStatus next) {
            return next == CANCEL_REQUESTED || next == CANCELED;
        }
    },
    /** 취소 요청 중 — 토스 취소 API 호출 전 의도 기록, 머니 동결 상태 */
    CANCEL_REQUESTED {
        @Override
        public boolean canTransitionTo(PaymentStatus next) {
            return next == SUCCESS || next == CANCELED;
        }
    },
    /** 취소 완료 — 토스 취소 확정 + DB 반영 */
    CANCELED {
        @Override
        public boolean canTransitionTo(PaymentStatus next) {
            return false;
        }
    };

    public abstract boolean canTransitionTo(PaymentStatus next);
}