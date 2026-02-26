package com.ureca.snac.payment.repository;

import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    /**
     * 고유 주문번호를 통해 Payment 조회
     * 결제 성공/ 실패 후 외부 결제 시스템으로 받은 주문번호로 원본 결제 찾는다.
     *
     * @param orderId 조회 주문번호
     * @return Payment 객체 optional
     */
    @Query("select p from Payment p join fetch p.member where p.orderId = :orderId")
    Optional<Payment> findByOrderId(@Param("orderId") String orderId);

    /**
     * 결제 키를 사용하여 Payment 조회할 때
     * Member 엔티티함께 해서 fetch join함
     *
     * @param paymentKey 토스가 발급한 결제 식별자
     * @return Payment 랑 Member 객체
     */
    @Query("select p from Payment p JOIN fetch p.member where p.paymentKey = :paymentKey")
    Optional<Payment> findByPaymentKeyWithMember(@Param("paymentKey") String paymentKey);

    /**
     * 결제 상태 변경 시 동시성 제어를 위한 비관적 락 조회
     * confirm/failure 처리 시 사용하여 race condition 방지
     *
     * @param orderId 주문번호
     * @return Payment with pessimistic write lock
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p join fetch p.member where p.orderId = :orderId")
    Optional<Payment> findByOrderIdWithMemberForUpdate(@Param("orderId") String orderId);

    /**
     * Payment ID로 비관적 락 조회
     * deposit 시점에 lock을 재획득하여 race condition 방지
     *
     * @param id Payment ID
     * @return Payment with pessimistic write lock
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p join fetch p.member where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    /**
     * 일정 시간 이상 PENDING 상태로 남아 있는 결제 건 조회 스케줄러에서 사용
     *
     * @param status    조회할 결제 상태
     * @param threshold 기준 시각 (이 시각 이전에 생성된 결제)
     * @param pageable  배치 크기 제어
     * @return 기준 시각 이전에 생성된 PENDING 결제 목록
     */
    @Query("select p from Payment p join fetch p.member where p.status = :status and p.createdAt < :threshold")
    List<Payment> findStalePendingPayments(@Param("status") PaymentStatus status,
                                           @Param("threshold") LocalDateTime threshold,
                                           Pageable pageable);

    /**
     * 여러 상태의 stale 결제 건 조회 (PENDING, CANCEL_REQUESTED)
     * updatedAt 사용: CANCEL_REQUESTED는 상태 변경 시점 기준으로 stale 판단
     */
    @Query("select p from Payment p join fetch p.member where p.status in :statuses and p.updatedAt < :threshold")
    List<Payment> findStalePayments(@Param("statuses") List<PaymentStatus> statuses,
                                    @Param("threshold") LocalDateTime threshold,
                                    Pageable pageable);
}
