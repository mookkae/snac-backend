package com.ureca.snac.integration;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.TransactionCategory;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentMethod;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
import com.ureca.snac.payment.service.PaymentInternalService;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentCancelResponseFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import com.ureca.snac.wallet.entity.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentInternalService 통합 테스트
 * <p>
 * processCancellationInDB : 결제 취소 시 DB 상태 변경 (Payment, Wallet, AssetHistory)
 * compensateCancellationFailure : 보상 처리 (REQUIRES_NEW 트랜잭션)
 * processCompensation: 보상 이벤트 처리
 */
@DisplayName("PaymentInternalService 통합 테스트")
class PaymentInternalServiceIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private PaymentInternalService paymentInternalService;

    private Member member;
    private Wallet wallet;
    private static final Long AMOUNT = 10000L;
    private static final Long INITIAL_BALANCE = 50000L;

    @BeforeEach
    void setUpMember() {
        member = memberRepository.save(MemberFixture.builder()
                .id(null)
                .email("test_" + System.currentTimeMillis() + "@snac.com")
                .build());
        wallet = walletRepository.save(Wallet.create(member));
        wallet.depositMoney(INITIAL_BALANCE);
        walletRepository.save(wallet);
    }

    @Nested
    @DisplayName("processCancellationInDB 메서드")
    class ProcessCancellationInDBTest {

        @Test
        @DisplayName("정상 : Payment 취소 + Wallet 출금 + AssetHistory 기록")
        void processCancellationInDB_HappyPath() {
            // given
            Payment payment = paymentRepository.save(PaymentFixture.builder()
                    .id(null)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.CARD)
                    .paidAt(OffsetDateTime.now())
                    .paymentKey("test_cancel_happy_" + System.currentTimeMillis())
                    .build());

            PaymentCancelResponse cancelResponse = PaymentCancelResponseFixture.create(
                    payment.getPaymentKey(), AMOUNT, "고객 요청");

            // prepareForCancellation: CANCEL_REQUESTED + freezeMoney (실제 취소 흐름과 동일하게)
            paymentInternalService.prepareForCancellation(payment.getId());

            // when
            paymentInternalService.processCancellationInDB(payment.getId(), cancelResponse);

            // then: Payment 상태 변경 확인
            Payment foundPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);

            // then: freeze 후 deductFrozenMoney → balance = INITIAL_BALANCE - AMOUNT
            Wallet foundWallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(foundWallet.getMoneyBalance()).isEqualTo(INITIAL_BALANCE - AMOUNT);

            // then: AssetHistory 기록 확인
            List<AssetHistory> histories = assetHistoryRepository.findAll();
            assertThat(histories).isNotEmpty();
            assertThat(histories.stream()
                    .anyMatch(h -> h.getCategory() == TransactionCategory.RECHARGE_CANCEL))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("compensateCancellationFailure 메서드")
    class CompensateCancellationFailureTest {

        @Test
        @DisplayName("정상 : Payment 상태 변경 없이 Outbox 이벤트만 저장")
        void compensateCancellationFailure_ShouldOnlyPersistOutbox_WithoutChangingPaymentStatus() {
            // given
            Payment payment = paymentRepository.save(PaymentFixture.builder()
                    .id(null)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .paymentKey("test_compensate_" + System.currentTimeMillis())
                    .build());

            PaymentCancelResponse cancelResponse = PaymentCancelResponseFixture.create(
                    payment.getPaymentKey(), AMOUNT, "Test cancel");

            Exception originalError = new RuntimeException("DB failure");

            // when
            paymentInternalService.compensateCancellationFailure(
                    payment, member.getId(), cancelResponse, originalError);

            // then: Payment 상태 변경 없음 — 대사 스케줄러 시야 내 유지
            Payment foundPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

            // then: Outbox 이벤트 저장
            List<Outbox> outboxes = outboxRepository.findAll();
            assertThat(outboxes).isNotEmpty();
            assertThat(outboxes.stream()
                    .anyMatch(o -> o.getEventType().contains("PaymentCancelCompensate")))
                    .isTrue();
        }

        @Test
        @DisplayName("정상 : outer 트랜잭션 없어도 Outbox 이벤트 독립 저장 (REQUIRES_NEW 실질 동작)")
        void compensateCancellationFailure_ShouldPersistOutboxEventIndependently() {
            // given: non-transactional 컨텍스트에서 호출 (cancelPayment가 @Transactional 없음)
            Payment payment = paymentRepository.save(PaymentFixture.builder()
                    .id(null)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .paymentKey("test_outer_rollback_" + System.currentTimeMillis())
                    .build());

            PaymentCancelResponse cancelResponse = PaymentCancelResponseFixture.create(
                    payment.getPaymentKey(), AMOUNT, "Test cancel outer");

            Exception originalError = new RuntimeException("DB failure");

            // when: outer 트랜잭션 없이 직접 호출
            paymentInternalService.compensateCancellationFailure(
                    payment, member.getId(), cancelResponse, originalError);

            // then: REQUIRES_NEW로 독립 커밋 — Outbox가 저장되어 있어야 함
            List<Outbox> outboxes = outboxRepository.findAll();
            assertThat(outboxes).isNotEmpty();
            assertThat(outboxes.stream()
                    .anyMatch(o -> o.getEventType().contains("PaymentCancelCompensate")))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("compensateCancellationFailure → processCompensation 연계")
    class CompensationChainTest {

        @Test
        @DisplayName("보상 후 processCompensation : frozen 소각 + CANCELED 전환")
        void compensationChain_FrozenDeductedAndPaymentCanceled() {
            // given: prepareForCancellation으로 CANCEL_REQUESTED + frozen 설정
            Payment payment = paymentRepository.save(PaymentFixture.builder()
                    .id(null)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.CARD)
                    .paidAt(OffsetDateTime.now())
                    .paymentKey("test_chain_" + System.currentTimeMillis())
                    .build());

            paymentInternalService.prepareForCancellation(payment.getId());

            // prepareForCancellation 후 가용 잔액 차감됨 (동결 상태)
            Wallet frozenWallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(frozenWallet.getMoneyBalance()).isEqualTo(INITIAL_BALANCE - AMOUNT);

            PaymentCancelResponse cancelResponse = PaymentCancelResponseFixture.create(
                    payment.getPaymentKey(), AMOUNT, "보상 체인 테스트");

            // when: compensateCancellationFailure (Outbox 이벤트만 발행)
            paymentInternalService.compensateCancellationFailure(
                    paymentRepository.findById(payment.getId()).orElseThrow(),
                    member.getId(), cancelResponse, new RuntimeException("DB 실패"));

            // then: 이 시점에 가용 잔액 여전히 차감된 상태
            Wallet midWallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(midWallet.getMoneyBalance()).isEqualTo(INITIAL_BALANCE - AMOUNT);

            // when: processCompensation (리스너가 처리하는 단계)
            paymentInternalService.processCompensation(new PaymentCancelCompensationEvent(
                    payment.getId(), member.getId(), AMOUNT, cancelResponse.reason(), cancelResponse.canceledAt()
            ));

            // then: frozen 소각 후 가용 잔액 확인 + Payment CANCELED
            Wallet finalWallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(finalWallet.getMoneyBalance()).isEqualTo(INITIAL_BALANCE - AMOUNT);

            Payment finalPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(finalPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        }
    }

    @Nested
    @DisplayName("processCompensation 메서드")
    class ProcessCompensationTest {

        @Test
        @DisplayName("정상 : Wallet 출금 + AssetHistory 기록")
        void processCompensation_HappyPath() {
            // given
            Payment payment = paymentRepository.save(PaymentFixture.builder()
                    .id(null)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.CARD)
                    .paidAt(OffsetDateTime.now())
                    .paymentKey("test_process_comp_" + System.currentTimeMillis())
                    .build());

            // prepareForCancellation: CANCEL_REQUESTED + freezeMoney (실제 취소 흐름과 동일하게)
            paymentInternalService.prepareForCancellation(payment.getId());

            PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                    payment.getId(),
                    member.getId(),
                    AMOUNT,
                    "보상 처리",
                    OffsetDateTime.now()
            );

            // when
            paymentInternalService.processCompensation(event);

            // then: freeze 후 deductFrozenMoney → balance = INITIAL_BALANCE - AMOUNT
            Wallet foundWallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(foundWallet.getMoneyBalance()).isEqualTo(INITIAL_BALANCE - AMOUNT);

            // then: AssetHistory 기록 확인
            List<AssetHistory> histories = assetHistoryRepository.findAll();
            assertThat(histories).isNotEmpty();
            assertThat(histories.stream()
                    .anyMatch(h -> h.getCategory() == TransactionCategory.RECHARGE_CANCEL))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("handleCancellationRejected 메서드")
    class HandleCancellationRejectedTest {

        @Test
        @DisplayName("정상 : frozen 해제 + Payment SUCCESS 복구")
        void handleCancellationRejected_HappyPath() {
            // given: prepareForCancellation으로 CANCEL_REQUESTED + frozen 상태 구성
            Payment payment = paymentRepository.save(PaymentFixture.builder()
                    .id(null)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.CARD)
                    .paidAt(OffsetDateTime.now())
                    .paymentKey("test_reject_" + System.currentTimeMillis())
                    .build());

            paymentInternalService.prepareForCancellation(payment.getId());

            // when
            paymentInternalService.handleCancellationRejected(payment.getId());

            // then: Payment 상태 SUCCESS 복구
            Payment found = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(found.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

            // then: frozen 해제 → 가용 잔액 원복
            Wallet restoredWallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(restoredWallet.getMoneyBalance()).isEqualTo(INITIAL_BALANCE);
        }
    }

    @Nested
    @DisplayName("processCompensation 멱등성")
    class ProcessCompensationIdempotencyTest {

        @Test
        @DisplayName("멱등성 : 동일 이벤트 2회 처리 시 Wallet 1회만 출금")
        void processCompensation_CalledTwice_WithdrawsOnlyOnce() {
            // given
            Payment payment = paymentRepository.save(PaymentFixture.builder()
                    .id(null)
                    .member(member)
                    .amount(AMOUNT)
                    .status(PaymentStatus.SUCCESS)
                    .method(PaymentMethod.CARD)
                    .paidAt(OffsetDateTime.now())
                    .paymentKey("test_idempotent_" + System.currentTimeMillis())
                    .build());

            // prepareForCancellation: CANCEL_REQUESTED + freezeMoney (실제 취소 흐름과 동일하게)
            paymentInternalService.prepareForCancellation(payment.getId());

            PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                    payment.getId(),
                    member.getId(),
                    AMOUNT,
                    "보상 처리",
                    OffsetDateTime.now()
            );

            // when: 동일 이벤트 2회 처리
            paymentInternalService.processCompensation(event);
            paymentInternalService.processCompensation(event);

            // then: freeze 후 deductFrozenMoney 1회 → balance = INITIAL_BALANCE - AMOUNT
            Wallet foundWallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(foundWallet.getMoneyBalance()).isEqualTo(INITIAL_BALANCE - AMOUNT);

            // then: AssetHistory 1건만 기록
            List<AssetHistory> histories = assetHistoryRepository.findAll();
            long cancelCount = histories.stream()
                    .filter(h -> h.getCategory() == TransactionCategory.RECHARGE_CANCEL)
                    .count();
            assertThat(cancelCount).isEqualTo(1);
        }
    }
}
