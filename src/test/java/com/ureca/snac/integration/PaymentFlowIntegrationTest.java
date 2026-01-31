package com.ureca.snac.integration;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.TransactionCategory;
import com.ureca.snac.infra.PaymentGatewayAdapter;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.dto.MoneyRechargePreparedResponse;
import com.ureca.snac.money.dto.MoneyRechargeRequest;
import com.ureca.snac.money.service.MoneyService;
import com.ureca.snac.payment.dto.PaymentCancelResponse;
import com.ureca.snac.payment.dto.PaymentFailureRequest;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.service.PaymentService;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.wallet.entity.Wallet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 결제 플로우 통합 테스트
 * <p>
 * 충전 성공: Payment SUCCESS -> Wallet 잔액 증가 -> AssetHistory 기록
 * 멱등성: 동일 orderId 중복 처리 시 예외
 * 취소: Payment CANCELED -> Wallet 잔액 감소 -> AssetHistory 취소 기록
 */
@DisplayName("결제 플로우 통합 테스트")
class PaymentFlowIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MoneyService moneyService;

    @Autowired
    private PaymentService paymentService;

    @MockitoBean
    private PaymentGatewayAdapter paymentGatewayAdapter;

    private Member member;

    private static final Long RECHARGE_AMOUNT = 10000L;
    private static final String PAYMENT_KEY = "toss_payment_key_test";

    @BeforeEach
    void setUpMember() {
        String uniqueEmail = "test_" + System.currentTimeMillis() + "@snac.com";
        String uniqueNickname = "user_" + System.currentTimeMillis();
        member = MemberFixture.builder()
                .id(null)
                .email(uniqueEmail)
                .nickname(uniqueNickname)
                .build();
        member = memberRepository.save(member);
        walletRepository.save(Wallet.create(member));
    }

    @Test
    @DisplayName("시나리오 1 : 충전 성공 -> Payment SUCCESS, Wallet 잔액 증가, AssetHistory 기록")
    void scenario1_RechargeSuccess_HappyPath() {
        // given
        MoneyRechargePreparedResponse prepared = prepareRecharge();
        mockTossConfirm(prepared.orderId());

        // when
        moneyService.processRechargeSuccess(
                PAYMENT_KEY + "_" + System.currentTimeMillis(),
                prepared.orderId(),
                RECHARGE_AMOUNT,
                member.getEmail()
        );

        // then 1 : Payment SUCCESS
        Payment payment = paymentRepository.findByOrderId(prepared.orderId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        // then 2 : Wallet 잔액 증가
        Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(wallet.getMoneyBalance()).isEqualTo(RECHARGE_AMOUNT);

        // then 3 : AssetHistory 기록
        List<AssetHistory> histories = assetHistoryRepository.findAll();
        assertThat(histories).hasSize(1);
        assertThat(histories.get(0).getCategory()).isEqualTo(TransactionCategory.RECHARGE);
    }

    @Test
    @DisplayName("시나리오 2 : 동일 orderId 중복 승인 시 예외 (멱등성)")
    void scenario2_DuplicateConfirmation_ThrowsException() {
        // given
        String uniquePaymentKey = PAYMENT_KEY + "_s2_" + System.currentTimeMillis();
        MoneyRechargePreparedResponse prepared = prepareRecharge();
        mockTossConfirmWithKey(uniquePaymentKey);

        // 첫 번째 승인
        moneyService.processRechargeSuccess(uniquePaymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());

        // when, then: 두 번째 승인 시도 -> 예외
        assertThatThrownBy(() ->
                moneyService.processRechargeSuccess(uniquePaymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail())
        ).isInstanceOf(Exception.class);

        // Wallet 잔액 1회만 충전
        Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(wallet.getMoneyBalance()).isEqualTo(RECHARGE_AMOUNT);
    }

    @Test
    @DisplayName("시나리오 3 : 결제 실패 기록 -> Payment CANCELED")
    void scenario3_PaymentFailure_RecordAsCanceled() {
        // given
        MoneyRechargePreparedResponse prepared = prepareRecharge();

        PaymentFailureRequest failureRequest = new PaymentFailureRequest(
                "INVALID_CARD_INFO", "카드 정보가 유효하지 않습니다", prepared.orderId(), null
        );

        // when
        paymentService.processPaymentFailure(failureRequest);

        // then
        Payment payment = paymentRepository.findByOrderId(prepared.orderId()).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);

        Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(wallet.getMoneyBalance()).isZero();
    }

    @Test
    @DisplayName("시나리오 4 : 충전 취소 -> Payment CANCELED, Wallet 잔액 감소, AssetHistory 취소 기록")
    void scenario4_CancelRecharge_HappyPath() {
        // given: 충전 완료
        String uniquePaymentKey = PAYMENT_KEY + "_s4_" + System.currentTimeMillis();
        MoneyRechargePreparedResponse prepared = prepareRecharge();
        mockTossConfirmWithKey(uniquePaymentKey);
        moneyService.processRechargeSuccess(uniquePaymentKey, prepared.orderId(), RECHARGE_AMOUNT, member.getEmail());

        mockTossCancel(uniquePaymentKey);

        // when
        paymentService.cancelPayment(uniquePaymentKey, "고객 요청", member.getEmail());

        // then 1 : Payment CANCELED
        Payment payment = paymentRepository.findByPaymentKeyWithMember(uniquePaymentKey).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);

        // then 2 : Wallet 잔액 0
        Wallet wallet = walletRepository.findByMemberId(member.getId()).orElseThrow();
        assertThat(wallet.getMoneyBalance()).isZero();

        // then 3 : AssetHistory 2건 (충전 + 취소)
        List<AssetHistory> histories = assetHistoryRepository.findAll();
        assertThat(histories).hasSize(2);
        assertThat(histories)
                .extracting(AssetHistory::getCategory)
                .containsExactlyInAnyOrder(TransactionCategory.RECHARGE, TransactionCategory.RECHARGE_CANCEL);
    }

    // 헬퍼

    private MoneyRechargePreparedResponse prepareRecharge() {
        return moneyService.prepareRecharge(new MoneyRechargeRequest(RECHARGE_AMOUNT), member.getEmail());
    }

    private void mockTossConfirm(String orderId) {
        TossConfirmResponse response = new TossConfirmResponse(
                PAYMENT_KEY + "_" + System.currentTimeMillis(), "카드", OffsetDateTime.now()
        );
        given(paymentGatewayAdapter.confirmPayment(anyString(), anyString(), anyLong())).willReturn(response);
    }

    private void mockTossConfirmWithKey(String paymentKey) {
        TossConfirmResponse response = new TossConfirmResponse(paymentKey, "카드", OffsetDateTime.now());
        given(paymentGatewayAdapter.confirmPayment(anyString(), anyString(), anyLong())).willReturn(response);
    }

    private void mockTossCancel(String paymentKey) {
        PaymentCancelResponse response = new PaymentCancelResponse(
                paymentKey, RECHARGE_AMOUNT, OffsetDateTime.now(), "고객 요청"
        );
        given(paymentGatewayAdapter.cancelPayment(anyString(), anyString())).willReturn(response);
    }
}
