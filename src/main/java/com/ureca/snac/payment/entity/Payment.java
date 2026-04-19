package com.ureca.snac.payment.entity;

import com.ureca.snac.common.BaseTimeEntity;
import com.ureca.snac.common.exception.BusinessException;
import com.ureca.snac.common.exception.InternalServerException;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.exception.*;
import jakarta.persistence.*;
import lombok.*;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.UUID;

import static com.ureca.snac.common.BaseCode.INVALID_INPUT;
import static com.ureca.snac.common.BaseCode.PAYMENT_INTERNAL_ERROR;

@Entity
@Table(name = "payment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(name = "order_id", nullable = false, unique = true)
    private String orderId;

    @Column(name = "payment_key", unique = true)
    private String paymentKey;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private OffsetDateTime paidAt;

    private String cancelReason;

    public static Payment prepare(Member member, Long amount) {
        if (amount == null || amount <= 0) {
            throw new BusinessException(INVALID_INPUT);
        }
        return Payment.builder()
                .member(member)
                .orderId("snac_order_" + UUID.randomUUID())
                .amount(amount)
                .status(PaymentStatus.PENDING)
                .build();
    }

    // 상태 완료
    public void complete(String paymentKey, PaymentMethod method, OffsetDateTime paidAt) {
        validateNotTerminated();
        this.paymentKey = paymentKey;
        this.method = method;
        this.paidAt = paidAt;
        this.status = PaymentStatus.SUCCESS;
    }

    // 취소 의도 기록 (SUCCESS -> CANCEL_REQUESTED)
    public void requestCancellation() {
        if (!this.status.canTransitionTo(PaymentStatus.CANCEL_REQUESTED)) {
            throw new PaymentNotCancellableException();
        }
        this.status = PaymentStatus.CANCEL_REQUESTED;
    }

    // NOT_CANCELABLE_PAYMENT 시에만 취소 거절로 인한 상태 복구 (CANCEL_REQUESTED -> SUCCESS)
    public void revertCancellation() {
        if (this.status != PaymentStatus.CANCEL_REQUESTED) {
            // 이 경로는 PG NOT_CANCELABLE_PAYMENT 응답 처리 전용 — CANCEL_REQUESTED 이외 상태 진입은 코드 버그
            throw new InternalServerException(PAYMENT_INTERNAL_ERROR,
                    "revertCancellation 잘못된 상태 진입: " + this.status);
        }
        this.status = PaymentStatus.SUCCESS;
    }

    // 허용된 상태에서만 취소 가능
    public void cancel(String reason) {
        if (!this.status.canTransitionTo(PaymentStatus.CANCELED)) {
            throw new PaymentNotCancellableException();
        }
        this.cancelReason = reason;
        this.status = PaymentStatus.CANCELED;
    }

    // 유효성 검증 메소드
    public void validateForConfirmation(Long memberId, Long amount) {
        // CANCEL_REQUESTED: 취소 진행 중 — 확정 불가. "이미 취소된 결제"와 구분
        validateNotTerminated();
        if (!isOwner(memberId)) {
            throw new PaymentOwnershipMismatchException();
        }
        if (!this.amount.equals(amount)) {
            throw new PaymentAmountMismatchException();
        }
    }

    public void validateForCancellation(Long memberId, Clock clock) {
        switch (this.status) {
            case CANCEL_REQUESTED -> throw new PaymentCancellationInProgressException();
            case CANCELED         -> throw new AlreadyCanceledPaymentException();
            case PENDING          -> throw new PaymentNotCancellableException();
            case SUCCESS          -> { /* 정상 취소 가능 상태 — 아래 검증 계속 */ }
        }
        if (!isOwner(memberId)) {
            throw new PaymentOwnershipMismatchException();
        }
        if (!isMethodCancelable()) {
            throw new PaymentMethodNotCancelableException();
        }
        if (isCancellationPeriodExpired(clock)) {
            throw new PaymentPeriodExpiredException();
        }
    }

    public void validateCancellationPeriodNotExpired(Clock clock) {
        if (isCancellationPeriodExpired(clock)) {
            throw new PaymentPeriodExpiredException();
        }
    }

    private boolean isMethodCancelable() {
        return this.method != null
                && this.method != PaymentMethod.VIRTUAL_ACCOUNT
                && this.method != PaymentMethod.UNKNOWN;
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private boolean isCancellationPeriodExpired(Clock clock) {
        // method=null: complete() 미호출 비정상 상태 → 안전상 취소 불가
        // paidAt=null: complete()가 항상 paidAt을 설정하므로 null이면 데이터 정합성 오류 → 안전상 취소 불가
        if (this.method == null || this.paidAt == null) {
            return true;
        }
        // 월 경계 비교는 KST 기준 — DB가 UTC로 저장하더라도 한국 결제 정책상 KST 월 기준
        ZonedDateTime paidAtKst = this.paidAt.atZoneSameInstant(KST);
        ZonedDateTime nowKst = ZonedDateTime.now(clock.withZone(KST));
        return this.method.isCancellationExpired(paidAtKst, nowKst);
    }

    // 종료 상태(SUCCESS·CANCEL_REQUESTED·CANCELED) 진입 불가 검증
    private void validateNotTerminated() {
        switch (this.status) {
            case SUCCESS          -> throw new PaymentAlreadySuccessException();
            case CANCEL_REQUESTED -> throw new PaymentCancellationInProgressException();
            case CANCELED         -> throw new AlreadyCanceledPaymentException();
            case PENDING          -> { /* 정상 진입 가능 상태 */ }
        }
    }

    // 소유주 검증
    private boolean isOwner(Long memberId) {
        if (this.member == null || memberId == null) {
            return false;
        }
        return Objects.equals(this.member.getId(), memberId);
    }
}
