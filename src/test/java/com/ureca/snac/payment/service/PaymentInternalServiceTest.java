package com.ureca.snac.payment.service;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.TransactionCategory;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.outbox.entity.Outbox;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.event.PaymentCancelCompensationEvent;
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
@DisplayName("PaymentInternalServiceTest 통합 테스트")
class PaymentInternalServiceTest extends IntegrationTestSupport {

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
                    .paymentKey("test_cancel_happy_" + System.currentTimeMillis())
                    .build());

            PaymentCancelResponse cancelResponse = PaymentCancelResponseFixture.create(
                    payment.getPaymentKey(), AMOUNT, "고객 요청");

            // when
            paymentInternalService.processCancellationInDB(payment, member, cancelResponse);

            // then: Payment 상태 변경 확인
            Payment foundPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);

            // then: Wallet 잔액 출금 확인
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
        @DisplayName("정상 : REQUIRES_NEW 트랜잭션으로 Outbox 이벤트 저장")
        void compensateCancellationFailure_ShouldPersistOutboxInSeparateTransaction() {
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
                    payment, member, cancelResponse, originalError);

            // then: Payment 상태 CANCELED로 변경
            Payment foundPayment = paymentRepository.findById(payment.getId()).orElseThrow();
            assertThat(foundPayment.getStatus()).isEqualTo(PaymentStatus.CANCELED);

            // then: Outbox 이벤트 저장
            List<Outbox> outboxes = outboxRepository.findAll();
            assertThat(outboxes).isNotEmpty();
            assertThat(outboxes.stream()
                    .anyMatch(o -> o.getEventType().contains("PaymentCancelCompensate")))
                    .isTrue();
        }

        @Test
        @DisplayName("정상 : Outer 트랜잭션 롤백과 무관하게 보상 이벤트 커밋")
        void compensateCancellationFailure_ShouldCommitIndependentlyOfOuterTransaction() {
            // given
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

            // when
            paymentInternalService.compensateCancellationFailure(
                    payment, member, cancelResponse, originalError);

            // then: Outbox 이벤트가 실제로 커밋됨
            List<Outbox> outboxes = outboxRepository.findAll();
            assertThat(outboxes).isNotEmpty();
            assertThat(outboxes.stream()
                    .anyMatch(o -> o.getEventType().contains("PaymentCancelCompensate")))
                    .isTrue();
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
                    .status(PaymentStatus.CANCELED)
                    .paymentKey("test_process_comp_" + System.currentTimeMillis())
                    .build());

            PaymentCancelCompensationEvent event = new PaymentCancelCompensationEvent(
                    payment.getId(),
                    member.getId(),
                    AMOUNT,
                    "보상 처리",
                    OffsetDateTime.now()
            );

            // when
            paymentInternalService.processCompensation(event);

            // then: Wallet 잔액 출금 확인
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
                    .status(PaymentStatus.CANCELED)
                    .paymentKey("test_idempotent_" + System.currentTimeMillis())
                    .build());

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

            // then: Wallet 잔액 1회만 출금
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
