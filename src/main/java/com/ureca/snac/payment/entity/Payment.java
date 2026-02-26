package com.ureca.snac.payment.entity;

import com.ureca.snac.common.BaseTimeEntity;
import com.ureca.snac.common.exception.BusinessException;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.exception.*;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import static com.ureca.snac.common.BaseCode.INVALID_INPUT;

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

    @Column(columnDefinition = "boolean default false")
    private boolean compensationCompleted;

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
        if (this.status != PaymentStatus.PENDING) {
            throw new PaymentAlreadyProcessedPaymentException();
        }
        this.paymentKey = paymentKey;
        this.method = method;
        this.paidAt = paidAt;
        this.status = PaymentStatus.SUCCESS;
    }

    // 취소 의도 기록 (SUCCESS → CANCEL_REQUESTED)
    public void requestCancellation() {
        if (this.status != PaymentStatus.SUCCESS) {
            throw new PaymentNotCancellableException();
        }
        this.status = PaymentStatus.CANCEL_REQUESTED;
    }

    // 상태 취소
    public void cancel(String reason) {
        if (this.status == PaymentStatus.CANCELED) {
            throw new PaymentNotCancellableException();
        }
        this.cancelReason = reason;
        this.status = PaymentStatus.CANCELED;
    }

    // 보상 처리 완료 플래그
    public void markCompensationCompleted() {
        this.compensationCompleted = true;
    }

    // 유효성 검증 메소드
    public void validateForConfirmation(Member member, Long amount) {
        if (this.status != PaymentStatus.PENDING) {
            throw new PaymentAlreadyProcessedPaymentException();
        }
        if (!isOwner(member)) {
            throw new PaymentOwnershipMismatchException();
        }
        if (!this.amount.equals(amount)) {
            throw new PaymentAmountMismatchException();
        }
    }

    public void validateForCancellation(Member member) {
        if (this.status != PaymentStatus.SUCCESS) {
            throw new PaymentNotCancellableException();
        }
        if (!isOwner(member)) {
            throw new PaymentOwnershipMismatchException();
        }
        if (isCancellationPeriodExpired()) {
            throw new PaymentPeriodExpiredException();
        }
    }

    private boolean isCancellationPeriodExpired() {
        if (this.method == null || this.paidAt == null) {
            return true;
        }
        OffsetDateTime now = OffsetDateTime.now();

        return switch (this.method) {
            case PHONE -> this.paidAt.getMonth() != now.getMonth() ||
                    this.paidAt.getYear() != now.getYear();
            case VIRTUAL_ACCOUNT -> true;  // API 취소 불가능
            default -> false;  // 카드 등 별도 기한 정책 없음
        };
    }

    // 소유주 검증
    private boolean isOwner(Member member) {
        if (this.member == null || member == null) {
            return false;
        }
        return Objects.equals(this.member.getId(), member.getId());
    }
}
