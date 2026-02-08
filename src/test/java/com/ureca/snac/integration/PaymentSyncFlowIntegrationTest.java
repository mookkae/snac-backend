package com.ureca.snac.integration;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.TransactionCategory;
import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.fixture.TossResponseFixture;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.money.entity.MoneyRecharge;
import com.ureca.snac.money.service.MoneyService;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.exception.AlreadyUsedRechargeCannotCancelException;
import com.ureca.snac.payment.exception.PaymentNotFoundException;
import com.ureca.snac.payment.service.PaymentService;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.PaymentCancelResponseFixture;
import com.ureca.snac.wallet.entity.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 결제 동기 플로우 통합 테스트
 * Controller -> Service -> DB -> External API 동기 플로우
 */
@DisplayName("결제 동기 플로우 통합 테스트")
class PaymentSyncFlowIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MoneyService moneyService;

    @Autowired
    private PaymentService paymentService;

    @MockitoBean
    private PaymentGatewayAdapter paymentGatewayAdapter;

    private Member member;

    private static final Long RECHARGE_AMOUNT = 10000L;
    private static final String PAYMENT_KEY = "toss_pk_sync_";

    @BeforeEach
    void setUpMember() {
        member = createMemberWithWallet("sync_");
    }

    @Nested
    @DisplayName("충전 준비")
    class PrepareRechargeTest {

        @Test
        @DisplayName("성공 : prepareRecharge -> Payment PENDING 생성")
        void shouldCreatePendingPayment() {
            // when
            MoneyRechargePreparedResponse prepared = prepareRecharge();

            // then
            Payment payment = paymentRepository.findByOrderId(prepared.orderId()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getOrderId()).startsWith("snac_order_");
            assertThat(prepared.amount()).isEqualTo(RECHARGE_AMOUNT);
        }

        @Test
        @DisplayName("실패 : 회원 없음 -> MemberNotFoundException")
        void shouldThrowWhenMemberNotFound() {
            // when, then
            assertThatThrownBy(() ->
                    moneyService.prepareRecharge(
                            new MoneyRechargeRequest(RECHARGE_AMOUNT), "nonexistent@snac.com")
            ).isInstanceOf(MemberNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("충전 성공")
    class RechargeSuccessTest {

        @Test
        @DisplayName("성공 : 충전 -> Payment SUCCESS + Wallet 증가 + MoneyRecharge + AssetHistory")
        void shouldCompleteRechargeSuccessfully() {
            // given
            MoneyRechargePreparedResponse prepared = prepareRecharge();
            String paymentKey = uniquePaymentKey();
            mockTossConfirm(paymentKey);

            // when
            moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());

            // then: Payment SUCCESS
            Payment payment = paymentRepository.findByOrderId(prepared.orderId()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

            // then: Wallet 잔액 증가
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isEqualTo(RECHARGE_AMOUNT);

            // then: MoneyRecharge 기록
            List<MoneyRecharge> recharges = moneyRechargeRepository.findAll();
            assertThat(recharges).hasSize(1);
            assertThat(recharges.get(0).getPaidAmountWon()).isEqualTo(RECHARGE_AMOUNT);

            // then: AssetHistory 기록
            List<AssetHistory> histories = assetHistoryRepository.findAll();
            assertThat(histories).hasSize(1);
            assertThat(histories.get(0).getCategory()).isEqualTo(TransactionCategory.RECHARGE);
        }

        @Test
        @DisplayName("멱등성 : 동일 orderId 중복 승인 -> 예외 + Wallet 1회만 반영")
        void shouldRejectDuplicateConfirmation() {
            // given
            MoneyRechargePreparedResponse prepared = prepareRecharge();
            String paymentKey = uniquePaymentKey();
            mockTossConfirm(paymentKey);

            moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());

            // when, then: 두 번째 승인 -> 예외
            assertThatThrownBy(() ->
                    moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail())
            ).isInstanceOf(Exception.class);

            // Wallet 잔액 1배만 반영
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isEqualTo(RECHARGE_AMOUNT);
        }

        @Test
        @DisplayName("실패 : 회원 없음 -> MemberNotFoundException")
        void shouldThrowWhenMemberNotFoundOnConfirm() {
            // given
            MoneyRechargePreparedResponse prepared = prepareRecharge();

            // when, then
            assertThatThrownBy(() ->
                    moneyService.processRechargeSuccess(
                            uniquePaymentKey(), prepared.orderId(), RECHARGE_AMOUNT, "nonexistent@snac.com")
            ).isInstanceOf(MemberNotFoundException.class);
        }

        @Test
        @DisplayName("실패 : Toss 승인 실패 -> Payment PENDING 유지, 취소 미호출")
        void shouldKeepPendingWhenTossConfirmFails() {
            // given
            MoneyRechargePreparedResponse prepared = prepareRecharge();
            String paymentKey = uniquePaymentKey();

            given(paymentGatewayAdapter.confirmPayment(anyString(), anyString(), anyLong()))
                    .willThrow(new RuntimeException("Toss 승인 실패"));

            // when, then
            assertThatThrownBy(() ->
                    moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail())
            ).isInstanceOf(RuntimeException.class);

            // Payment PENDING 유지
            Payment payment = paymentRepository.findByOrderId(prepared.orderId()).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);

            // cancel 미호출 (Toss 승인 실패 -> 돈 안 빠짐)
            verify(paymentGatewayAdapter, never()).cancelPayment(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("취소")
    class CancelTest {

        @Test
        @DisplayName("성공 : 취소 -> Payment CANCELED + Wallet 출금 + AssetHistory 2건")
        void shouldCancelPaymentSuccessfully() {
            // given: 충전 완료
            String paymentKey = uniquePaymentKey();
            prepareAndCompleteRecharge(paymentKey);
            mockTossCancel(paymentKey);

            // when
            paymentService.cancelPayment(paymentKey, "고객 요청", member.getEmail());

            // then: Payment CANCELED
            Payment payment = paymentRepository.findByPaymentKeyWithMember(paymentKey).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);

            // then: Wallet 잔액 0
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            assertThat(wallet.getMoneyBalance()).isZero();

            // then: AssetHistory 2건 (충전 + 취소)
            List<AssetHistory> histories = assetHistoryRepository.findAll();
            assertThat(histories).hasSize(2);
            assertThat(histories)
                    .extracting(AssetHistory::getCategory)
                    .containsExactlyInAnyOrder(TransactionCategory.RECHARGE, TransactionCategory.RECHARGE_CANCEL);
        }

        @Test
        @DisplayName("실패 : 잔액 부족 -> AlreadyUsedRechargeCannotCancelException")
        void shouldRejectCancelWhenBalanceInsufficient() {
            // given: 충전 완료 후 잔액을 임의로 줄여서 잔액 부족 시뮬레이션
            String paymentKey = uniquePaymentKey();
            prepareAndCompleteRecharge(paymentKey);

            // Wallet 잔액을 0으로 만들어서 취소 불가 상태 만듦
            Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
            wallet.withdrawMoney(RECHARGE_AMOUNT);
            walletRepository.saveAndFlush(wallet);

            // when, then
            assertThatThrownBy(() ->
                    paymentService.cancelPayment(paymentKey, "고객 요청", member.getEmail())
            ).isInstanceOf(AlreadyUsedRechargeCannotCancelException.class);

            // Payment SUCCESS 유지
            Payment payment = paymentRepository.findByPaymentKeyWithMember(paymentKey).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        }

        @Test
        @DisplayName("실패 : 결제 없음 -> PaymentNotFoundException")
        void shouldThrowWhenPaymentNotFound() {
            // when, then
            assertThatThrownBy(() ->
                    paymentService.cancelPayment("nonexistent_pk", "고객 요청", member.getEmail())
            ).isInstanceOf(PaymentNotFoundException.class);
        }
    }

    // ================= Helper ====================

    private MoneyRechargePreparedResponse prepareRecharge() {
        return moneyService.prepareRecharge(new MoneyRechargeRequest(RECHARGE_AMOUNT), member.getEmail());
    }

    private void prepareAndCompleteRecharge(String paymentKey) {
        MoneyRechargePreparedResponse prepared = prepareRecharge();
        mockTossConfirm(paymentKey);
        moneyService.processRechargeSuccess(paymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());
    }

    private void mockTossConfirm(String paymentKey) {
        given(paymentGatewayAdapter.confirmPayment(anyString(), anyString(), anyLong()))
                .willReturn(TossResponseFixture.createConfirmResponse(paymentKey));
    }

    private void mockTossCancel(String paymentKey) {
        given(paymentGatewayAdapter.cancelPayment(anyString(), anyString()))
                .willReturn(PaymentCancelResponseFixture.create(paymentKey, RECHARGE_AMOUNT, "고객 요청"));
    }

    private String uniquePaymentKey() {
        return PAYMENT_KEY + System.currentTimeMillis();
    }
}
