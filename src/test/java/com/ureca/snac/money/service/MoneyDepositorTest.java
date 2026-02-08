package com.ureca.snac.money.service;

import com.ureca.snac.asset.service.AssetRecorder;
import com.ureca.snac.infra.dto.response.TossConfirmResponse;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.repository.MoneyRechargeRepository;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import com.ureca.snac.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * MoneyDepositor 단위 테스트 (Spring Support)
 *
 * @Retryable AOP 동작 검증을 위해 Spring Context를 로드하지만,
 * 모든 협력 객체는 Mock으로 대체하여 단위 기능을 검증함.
 */
@DisplayName("MoneyDepositor 단위 테스트")
class MoneyDepositorTest extends IntegrationTestSupport {

    @Autowired
    private MoneyDepositor moneyDepositor;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private MoneyRechargeRepository moneyRechargeRepository;

    @MockitoBean
    private WalletService walletService;

    @MockitoBean
    private AssetRecorder assetRecorder;

    private Member member;
    private Payment payment;
    private TossConfirmResponse tossConfirmResponse;

    private static final String PAYMENT_KEY = "test_payment_key";
    private static final Long AMOUNT = 10000L;

    @BeforeEach
    void setUp() {
        member = MemberFixture.createMember(1L);
        payment = PaymentFixture.createPendingPayment(member);
        tossConfirmResponse = new TossConfirmResponse(
                PAYMENT_KEY, "카드", OffsetDateTime.now()
        );
    }

    @Nested
    @DisplayName("deposit 메서드")
    class DepositTest {

        @Nested
        @DisplayName("재시도 동작")
        class RetryBehaviorTest {

            @Test
            @DisplayName("정상 : TransientDataAccessException 발생 시 최대 3회 재시도")
            void deposit_ShouldRetryOnTransientDataAccessException() {
                // given: FOR UPDATE 락 조회 시 일시적 DB 오류
                given(paymentRepository.findByIdForUpdate(any()))
                        .willThrow(new TransientDataAccessException("Connection timeout") {
                        });

                // when & then: 예외 발생하며 3회 호출됨
                assertThatThrownBy(() ->
                        moneyDepositor.deposit(payment, member, tossConfirmResponse)
                ).isInstanceOf(TransientDataAccessException.class);

                verify(paymentRepository, times(3)).findByIdForUpdate(any());
            }

            @Test
            @DisplayName("정상 : 2회 실패 후 3회차에 성공하면 정상 완료")
            void deposit_ShouldSucceedOnThirdAttempt() {
                // given: 2번 실패 후 3번째 성공
                given(paymentRepository.findByIdForUpdate(any()))
                        .willThrow(new TransientDataAccessException("Connection timeout") {
                        })
                        .willThrow(new TransientDataAccessException("Connection timeout") {
                        })
                        .willReturn(Optional.of(payment));

                // MoneyRecharge 저장 mock (null 방지)
                given(moneyRechargeRepository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

                given(walletService.depositMoney(anyLong(), anyLong())).willReturn(AMOUNT);

                // when: 예외 없이 완료
                moneyDepositor.deposit(payment, member, tossConfirmResponse);

                // then: 총 3회 호출
                verify(paymentRepository, times(3)).findByIdForUpdate(any());
                verify(walletService, times(1)).depositMoney(member.getId(), AMOUNT);
            }

            @Test
            @DisplayName("예외 : 재시도 불가능한 예외는 즉시 전파")
            void deposit_ShouldNotRetryOnNonRetryableException() {
                // given: FOR UPDATE 락 조회 시 일반 예외
                given(paymentRepository.findByIdForUpdate(any()))
                        .willThrow(new IllegalStateException("Non-retryable error"));

                // when & then: 예외 발생하며 1회만 호출됨
                assertThatThrownBy(() ->
                        moneyDepositor.deposit(payment, member, tossConfirmResponse)
                ).isInstanceOf(IllegalStateException.class);

                verify(paymentRepository, times(1)).findByIdForUpdate(any());
            }
        }

        @Nested
        @DisplayName("멱등성 처리")
        class IdempotencyTest {

            @Test
            @DisplayName("정상 : 이미 처리된 충전 요청(SUCCESS 상태)은 재시도 없이 즉시 반환")
            void deposit_ShouldReturnImmediatelyIfAlreadyProcessed() {
                // given: FOR UPDATE 락 조회 시 이미 SUCCESS 상태인 Payment 반환
                Payment successPayment = PaymentFixture.createSuccessPayment(member);
                given(paymentRepository.findByIdForUpdate(any())).willReturn(Optional.of(successPayment));

                // when: 예외 없이 즉시 반환
                moneyDepositor.deposit(payment, member, tossConfirmResponse);

                // then: MoneyRecharge 저장 호출 안 됨
                verify(moneyRechargeRepository, never()).save(any());
                verify(walletService, never()).depositMoney(anyLong(), anyLong());
            }
        }
    }
}
