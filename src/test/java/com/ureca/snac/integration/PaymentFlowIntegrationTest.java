package com.ureca.snac.integration;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 결제 플로우 통합 테스트
 * <p>
 * 충전 성공 플로우 : Payment → Wallet 잔액 증가 -> AssetHistory 기록
 * 멱등성 : 동일 orderId 중복 처리 시 예외
 * 취소 플로우 : Payment CANCELED -> Wallet 잔액 감소 -? AssetHistory 취소 기록
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
        // 회원 생성
        String uniqueEmail = "test_" + System.currentTimeMillis() + "@snac.com";
        String uniqueNickname = "user_" + System.currentTimeMillis();
        member = MemberFixture.builder()
                .id(null)
                .email(uniqueEmail)
                .nickname(uniqueNickname)
                .build();
        member = memberRepository.save(member);

        // 지갑 생성
        walletRepository.save(Wallet.create(member));
    }

    @Test
    @DisplayName("시나리오 1 : 충전 성공 -> Payment SUCCESS, Wallet 잔액 증가, AssetHistory 기록")
    void scenario1_RechargeSuccess_HappyPath() {
        // given : 충전 준비
        MoneyRechargePreparedResponse prepared = moneyService.prepareRecharge(
                new MoneyRechargeRequest(RECHARGE_AMOUNT),
                member.getEmail()
        );

        // Toss
        TossConfirmResponse tossResponse = new TossConfirmResponse(
                PAYMENT_KEY + "_" + System.currentTimeMillis(),
                "카드",
                OffsetDateTime.now()
        );
        given(paymentGatewayAdapter.confirmPayment(anyString(), anyString(), anyLong()))
                .willReturn(tossResponse);

        // when : 충전 성공
        moneyService.processRechargeSuccess(
                tossResponse.paymentKey(),
                prepared.orderId(),
                RECHARGE_AMOUNT,
                member.getEmail()
        );

        // then 1 : Payment 상태 SUCCESS
        Payment payment = paymentRepository.findByOrderId(prepared.orderId())
                .orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS);

        // then 2 : Wallet 잔액 증가
        Wallet updatedWallet = walletRepository.findByMemberId(member.getId())
                .orElseThrow();
        assertThat(updatedWallet.getMoneyBalance()).isEqualTo(RECHARGE_AMOUNT);
    }

    @Test
    @DisplayName("시나리오 2 : 동일 orderId 중복 승인 시 예외 (멱등성)")
    void scenario2_DuplicateConfirmation_ThrowsException() {
        // given : 충전 준비 및 첫 번째 승인 완료
        String uniquePaymentKey = PAYMENT_KEY + "_s2_" + System.currentTimeMillis();

        MoneyRechargePreparedResponse prepared = moneyService.prepareRecharge(
                new MoneyRechargeRequest(RECHARGE_AMOUNT),
                member.getEmail()
        );

        TossConfirmResponse tossResponse = new TossConfirmResponse(
                uniquePaymentKey,
                "카드",
                OffsetDateTime.now()
        );
        given(paymentGatewayAdapter.confirmPayment(anyString(), anyString(), anyLong()))
                .willReturn(tossResponse);

        // 첫 번째 승인
        moneyService.processRechargeSuccess(
                uniquePaymentKey,
                prepared.orderId(),
                RECHARGE_AMOUNT,
                member.getEmail()
        );

        // when, then: 두 번째 승인 시도 -> 예외
        assertThatThrownBy(() ->
                moneyService.processRechargeSuccess(
                        uniquePaymentKey,
                        prepared.orderId(),
                        RECHARGE_AMOUNT,
                        member.getEmail()
                )
        ).isInstanceOf(Exception.class);

        // Wallet 잔액은 여전히 1회 충전 금액
        Wallet finalWallet = walletRepository.findByMemberId(member.getId())
                .orElseThrow();
        assertThat(finalWallet.getMoneyBalance()).isEqualTo(RECHARGE_AMOUNT);
    }

    @Test
    @DisplayName("시나리오 3 : 결제 실패 기록 -> Payment CANCELED")
    void scenario3_PaymentFailure_RecordAsCanceled() {
        // given : 충전 준비
        MoneyRechargePreparedResponse prepared = moneyService.prepareRecharge(
                new MoneyRechargeRequest(RECHARGE_AMOUNT),
                member.getEmail()
        );

        PaymentFailureRequest failureRequest = new PaymentFailureRequest(
                "INVALID_CARD_INFO",
                "카드 정보가 유효하지 않습니다",
                prepared.orderId(),
                null
        );

        // when : 실패 기록
        paymentService.processPaymentFailure(failureRequest);

        // then : Payment 상태 CANCELED
        Payment payment = paymentRepository.findByOrderId(prepared.orderId())
                .orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(payment.getFailureCode()).isEqualTo("INVALID_CARD_INFO");

        // Wallet 잔액 변동 없음
        Wallet unchangedWallet = walletRepository.findByMemberId(member.getId())
                .orElseThrow();
        assertThat(unchangedWallet.getMoneyBalance()).isZero();
    }

    @Test
    @DisplayName("시나리오 4 : 충전 취소 -> Payment CANCELED, Wallet 잔액 감소, AssetHistory 취소 기록")
    void scenario4_CancelRecharge_HappyPath() {
        // given : 충전 성공 상태 만들기
        String uniquePaymentKey = PAYMENT_KEY + "_s4_" + System.currentTimeMillis();

        MoneyRechargePreparedResponse prepared = moneyService.prepareRecharge(
                new MoneyRechargeRequest(RECHARGE_AMOUNT),
                member.getEmail()
        );

        TossConfirmResponse tossConfirmResponse = new TossConfirmResponse(
                uniquePaymentKey,
                "카드",
                OffsetDateTime.now()
        );
        given(paymentGatewayAdapter.confirmPayment(anyString(), anyString(), anyLong()))
                .willReturn(tossConfirmResponse);

        moneyService.processRechargeSuccess(
                uniquePaymentKey,
                prepared.orderId(),
                RECHARGE_AMOUNT,
                member.getEmail()
        );

        // 취소 API
        PaymentCancelResponse cancelResponse = new PaymentCancelResponse(
                uniquePaymentKey,
                RECHARGE_AMOUNT,
                OffsetDateTime.now(),
                "고객 요청"
        );
        given(paymentGatewayAdapter.cancelPayment(anyString(), anyString()))
                .willReturn(cancelResponse);

        // when : 취소 요청
        paymentService.cancelPayment(uniquePaymentKey, "고객 요청", member.getEmail());

        // then 1 : Payment 상태 CANCELED
        Payment payment = paymentRepository.findByPaymentKeyWithMember(uniquePaymentKey)
                .orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELED);

        // then 2 : Wallet 잔액 0 (회수됨)
        Wallet finalWallet = walletRepository.findByMemberId(member.getId())
                .orElseThrow();
        assertThat(finalWallet.getMoneyBalance()).isZero();

        // then 3 : AssetHistory에 충전 + 취소 2건 기록
        assertThat(assetHistoryRepository.findAll()).hasSize(2);
    }
}
