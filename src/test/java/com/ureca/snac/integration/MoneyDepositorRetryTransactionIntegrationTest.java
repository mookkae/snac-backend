package com.ureca.snac.integration;

import com.ureca.snac.payment.port.out.dto.PaymentConfirmResult;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.money.repository.MoneyRechargeRepository;
import com.ureca.snac.money.service.MoneyDepositorRetryFacade;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.support.IntegrationTestSupport;
import com.ureca.snac.wallet.service.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * MoneyDepositor @Retryable + @Transactional 프록시 순서 회귀 방지 통합 테스트
 * Retry(outer) → Transaction(inner) 순서를 실제 MySQL 트랜잭션으로 검증한다.
 * Transaction(outer)이면 rollback-only 트랜잭션 안에서 재시도가 실행되어 UnexpectedRollbackException 발생.
 */
@DisplayName("MoneyDepositor @Retryable + @Transactional 프록시 순서 통합 검증")
class MoneyDepositorRetryTransactionIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private MoneyDepositorRetryFacade moneyDepositor;

    @MockitoSpyBean
    private WalletService walletService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private MoneyRechargeRepository moneyRechargeRepository;

    private static final String PAYMENT_KEY = "test_payment_key_tx_order";
    private static final Long AMOUNT = 10000L;

    @Test
    @DisplayName("1차 시도에서 TransientDataAccessException 발생 후 재시도가 새로운 트랜잭션으로 성공한다")
    void deposit_ShouldSucceedOnRetry_WithFreshTransaction() {
        // given
        Member member = createMemberWithWallet("tx-order");
        Payment payment = Payment.prepare(member, AMOUNT);
        paymentRepository.save(payment);

        PaymentConfirmResult tossResponse = new PaymentConfirmResult(PAYMENT_KEY, "카드", OffsetDateTime.now());

        // 1차 호출: TransientDataAccessException → deposit() 트랜잭션 rollback-only 유발
        // 2차 호출: 실제 메서드 실행
        doThrow(new TransientDataAccessException("Simulated transient DB failure") {})
                .doCallRealMethod()
                .when(walletService).depositMoney(anyLong(), anyLong());

        // when & then
        assertThatCode(() -> moneyDepositor.deposit(payment.getId(), member.getId(), tossResponse))
                .as("Retry(outer) → Transaction(inner) 순서 → 2차 재시도가 새로운 트랜잭션으로 성공해야 함")
                .doesNotThrowAnyException();

        // then: 2차 시도 트랜잭션이 온전히 커밋되었는지 DB로 검증
        Payment saved = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(saved.getPaymentKey()).isEqualTo(PAYMENT_KEY);

        assertThat(moneyRechargeRepository.findAll())
                .as("1차 롤백분은 포함되지 않고 2차 커밋분만 존재해야 함")
                .hasSize(1);
    }
}