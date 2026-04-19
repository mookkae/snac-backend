package com.ureca.snac.payment.scheduler;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.entity.PaymentStatus;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentReconciliationScheduler 단위 테스트")
class PaymentReconciliationSchedulerTest {

    private PaymentReconciliationScheduler scheduler;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentReconciliationOrchestrator orchestrator;

    private final Member member = MemberFixture.createMember(1L);

    @BeforeEach
    void setUp() {
        scheduler = new PaymentReconciliationScheduler(
                paymentRepository,
                orchestrator,
                10,  // staleThresholdMinutes
                50,  // batchSize
                Clock.systemDefaultZone()
        );
    }

    @Test
    @DisplayName("미결 결제 없음 -> 서비스 호출 없이 조기 종료")
    void shouldEarlyExitWhenNoStalePayments() {
        given(paymentRepository.findStalePayments(any(), any(), eq(PageRequest.of(0, 50))))
                .willReturn(List.of());

        scheduler.reconcileStalePayments();

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("미결 결제 N건 -> reconcile N회 호출")
    void shouldDelegateEachPaymentToService() {
        Payment p1 = PaymentFixture.builder().id(1L).member(member).status(PaymentStatus.PENDING).build();
        Payment p2 = PaymentFixture.builder().id(2L).member(member).status(PaymentStatus.CANCEL_REQUESTED).build();

        given(paymentRepository.findStalePayments(any(), any(), any()))
                .willReturn(List.of(p1, p2));

        scheduler.reconcileStalePayments();

        verify(orchestrator).reconcile(p1);
        verify(orchestrator).reconcile(p2);
    }

    @Test
    @DisplayName("한 건 예외 발생 -> 나머지 건 계속 처리")
    void shouldContinueOnExceptionPerPayment() {
        Payment p1 = PaymentFixture.builder().id(1L).member(member).status(PaymentStatus.PENDING).build();
        Payment p2 = PaymentFixture.builder().id(2L).member(member).status(PaymentStatus.PENDING).build();

        given(paymentRepository.findStalePayments(any(), any(), any()))
                .willReturn(List.of(p1, p2));
        doThrow(new RuntimeException("DB error")).when(orchestrator).reconcile(p1);

        scheduler.reconcileStalePayments();

        verify(orchestrator).reconcile(p1);
        verify(orchestrator).reconcile(p2);
    }
}
