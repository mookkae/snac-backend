package com.ureca.snac.wallet.service;

import com.ureca.snac.member.entity.Member;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.WalletFixture;
import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.dto.WalletSummaryResponse;
import com.ureca.snac.wallet.entity.Wallet;
import com.ureca.snac.wallet.event.WalletCreatedEvent;
import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import com.ureca.snac.wallet.exception.InvalidAmountException;
import com.ureca.snac.wallet.exception.WalletNotFoundException;
import com.ureca.snac.wallet.repository.WalletRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * WalletService 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private WalletServiceImpl walletService;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        walletService = new WalletServiceImpl(walletRepository, eventPublisher, meterRegistry);
    }

    @Nested
    @DisplayName("지갑 생성")
    class CreateWalletTest {

        @Test
        @DisplayName("멱등성 : 이미 지갑이 있으면 생성 안 함")
        void createWallet_Idempotent() {
            // given
            Member member = MemberFixture.createMember(1L);
            given(walletRepository.existsByMemberId(member.getId()))
                    .willReturn(true);

            // when
            walletService.createWallet(member);

            // then
            verify(walletRepository, never())
                    .save(any());
            verify(eventPublisher, never())
                    .publishEvent(any(WalletCreatedEvent.class));
        }

        @Test
        @DisplayName("정상 : 지갑 생성 및 WalletCreatedEvent 발행")
        void createWallet_Success() {
            // given
            Member member = MemberFixture.createMember(1L);
            given(walletRepository.existsByMemberId(member.getId()))
                    .willReturn(false);

            // when
            walletService.createWallet(member);

            // then
            verify(walletRepository, times(1))
                    .save(any());
            verify(eventPublisher, times(1))
                    .publishEvent(any(WalletCreatedEvent.class));
        }
    }

    @Nested
    @DisplayName("단건 입출금 메서드")
    class SingleOperationTest {

        private Member member;
        private Wallet wallet;

        @BeforeEach
        void setUp() {
            member = MemberFixture.createMember(1L);
            wallet = WalletFixture.createEmptyWallet(member);
            wallet.depositMoney(10000L);
            wallet.depositPoint(5000L);
        }

        @Test
        @DisplayName("정상 : depositMoney - 입금 후 잔액 반환")
        void depositMoney_shouldReturnFinalBalance() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            Long finalBalance = walletService.depositMoney(member.getId(), 3000L);

            // then
            assertThat(finalBalance).isEqualTo(13000L);
        }

        @Test
        @DisplayName("정상 : withdrawMoney - 출금 후 잔액 반환")
        void withdrawMoney_shouldReturnFinalBalance() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            Long finalBalance = walletService.withdrawMoney(member.getId(), 4000L);

            // then
            assertThat(finalBalance).isEqualTo(6000L);
        }

        @Test
        @DisplayName("예외 : withdrawMoney - 잔액 부족 시 InsufficientBalanceException")
        void withdrawMoney_insufficientBalance_shouldThrow() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then
            assertThatThrownBy(() -> walletService.withdrawMoney(member.getId(), 20000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("정상 : depositPoint - 적립 후 잔액 반환")
        void depositPoint_shouldReturnFinalBalance() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            Long finalBalance = walletService.depositPoint(member.getId(), 2000L);

            // then
            assertThat(finalBalance).isEqualTo(7000L);
        }

        @Test
        @DisplayName("예외 : depositMoney - 지갑 없으면 WalletNotFoundException")
        void depositMoney_walletNotFound_shouldThrow() {
            // given
            given(walletRepository.findByMemberIdWithLock(99L))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> walletService.depositMoney(99L, 1000L))
                    .isInstanceOf(WalletNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("복합 에스크로 메서드")
    class CompositeEscrowTest {

        private Member member;
        private Wallet wallet;

        @BeforeEach
        void setUp() {
            member = MemberFixture.createMember(1L);
            wallet = WalletFixture.createEmptyWallet(member);
            wallet.depositMoney(10000L);
            wallet.depositPoint(5000L);
        }

        @Test
        @DisplayName("정상 : moveCompositeToEscrow - 머니+포인트 에스크로 이동")
        void moveCompositeToEscrow_shouldMoveBoth() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.moveCompositeToEscrow(member.getId(), 7000L, 3000L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(3000L);
            assertThat(result.moneyEscrow()).isEqualTo(7000L);
            assertThat(result.pointBalance()).isEqualTo(2000L);
            assertThat(result.pointEscrow()).isEqualTo(3000L);
        }

        @Test
        @DisplayName("정상 : releaseCompositeEscrow - 머니+포인트 에스크로 복원")
        void releaseCompositeEscrow_shouldReleaseBoth() {
            // given
            wallet.moveMoneyToEscrow(10000L);
            wallet.movePointToEscrow(5000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.releaseCompositeEscrow(member.getId(), 6000L, 2000L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(6000L);
            assertThat(result.moneyEscrow()).isEqualTo(4000L);
            assertThat(result.pointBalance()).isEqualTo(2000L);
            assertThat(result.pointEscrow()).isEqualTo(3000L);
        }

        @Test
        @DisplayName("정상 : deductCompositeEscrow - 머니+포인트 에스크로 차감 (소멸)")
        void deductCompositeEscrow_shouldDeductBoth() {
            // given
            wallet.moveMoneyToEscrow(10000L);
            wallet.movePointToEscrow(5000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.deductCompositeEscrow(member.getId(), 10000L, 5000L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(0L);
            assertThat(result.moneyEscrow()).isEqualTo(0L);
            assertThat(result.pointBalance()).isEqualTo(0L);
            assertThat(result.pointEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("정상 : moveCompositeToEscrow - 머니만 사용 (포인트=0)")
        void moveCompositeToEscrow_moneyOnly_shouldMoveMoneyOnly() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.moveCompositeToEscrow(member.getId(), 5000L, 0L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(5000L);
            assertThat(result.moneyEscrow()).isEqualTo(5000L);
            assertThat(result.pointBalance()).isEqualTo(5000L);
            assertThat(result.pointEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("정상 : moveCompositeToEscrow - 포인트만 사용 (머니=0)")
        void moveCompositeToEscrow_pointOnly_shouldMovePointOnly() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.moveCompositeToEscrow(member.getId(), 0L, 3000L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(10000L);
            assertThat(result.moneyEscrow()).isEqualTo(0L);
            assertThat(result.pointBalance()).isEqualTo(2000L);
            assertThat(result.pointEscrow()).isEqualTo(3000L);
        }

        @Test
        @DisplayName("예외 : moveCompositeToEscrow - 둘 다 0 이하이면 InvalidAmountException")
        void moveCompositeToEscrow_bothZero_shouldThrow() {
            // when & then
            assertThatThrownBy(() -> walletService.moveCompositeToEscrow(member.getId(), 0L, 0L))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("정상 : releaseCompositeEscrow - 머니만 복원 (포인트=0)")
        void releaseCompositeEscrow_moneyOnly_shouldReleaseMoneyOnly() {
            // given
            wallet.moveMoneyToEscrow(10000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.releaseCompositeEscrow(member.getId(), 5000L, 0L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(5000L);
            assertThat(result.moneyEscrow()).isEqualTo(5000L);
            assertThat(result.pointBalance()).isEqualTo(5000L);
            assertThat(result.pointEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("정상 : releaseCompositeEscrow - 포인트만 복원 (머니=0)")
        void releaseCompositeEscrow_pointOnly_shouldReleasePointOnly() {
            // given
            wallet.movePointToEscrow(5000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.releaseCompositeEscrow(member.getId(), 0L, 3000L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(10000L);
            assertThat(result.moneyEscrow()).isEqualTo(0L);
            assertThat(result.pointBalance()).isEqualTo(3000L);
            assertThat(result.pointEscrow()).isEqualTo(2000L);
        }

        @Test
        @DisplayName("예외 : releaseCompositeEscrow - 둘 다 0 이하이면 InvalidAmountException")
        void releaseCompositeEscrow_bothZero_shouldThrow() {
            // when & then
            assertThatThrownBy(() -> walletService.releaseCompositeEscrow(member.getId(), 0L, 0L))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("정상 : deductCompositeEscrow - 머니만 차감 (포인트=0)")
        void deductCompositeEscrow_moneyOnly_shouldDeductMoneyOnly() {
            // given
            wallet.moveMoneyToEscrow(10000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.deductCompositeEscrow(member.getId(), 10000L, 0L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(0L);
            assertThat(result.moneyEscrow()).isEqualTo(0L);
            assertThat(result.pointBalance()).isEqualTo(5000L);
            assertThat(result.pointEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("정상 : deductCompositeEscrow - 포인트만 차감 (머니=0)")
        void deductCompositeEscrow_pointOnly_shouldDeductPointOnly() {
            // given
            wallet.movePointToEscrow(5000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.deductCompositeEscrow(member.getId(), 0L, 5000L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(10000L);
            assertThat(result.moneyEscrow()).isEqualTo(0L);
            assertThat(result.pointBalance()).isEqualTo(0L);
            assertThat(result.pointEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("예외 : deductCompositeEscrow - 둘 다 0 이하이면 InvalidAmountException")
        void deductCompositeEscrow_bothZero_shouldThrow() {
            // when & then
            assertThatThrownBy(() -> walletService.deductCompositeEscrow(member.getId(), 0L, 0L))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("예외 : moveCompositeToEscrow - 머니 잔액 부족 시 InsufficientBalanceException")
        void moveCompositeToEscrow_insufficientMoney_shouldThrow() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (잔액 10000, 요청 15000)
            assertThatThrownBy(() -> walletService.moveCompositeToEscrow(member.getId(), 15000L, 1000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : moveCompositeToEscrow - 포인트 잔액 부족 시 InsufficientBalanceException")
        void moveCompositeToEscrow_insufficientPoint_shouldThrow() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (포인트 잔액 5000, 요청 8000)
            assertThatThrownBy(() -> walletService.moveCompositeToEscrow(member.getId(), 3000L, 8000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : releaseCompositeEscrow - 에스크로 잔액 부족 시 InsufficientBalanceException")
        void releaseCompositeEscrow_insufficientEscrow_shouldThrow() {
            // given
            wallet.moveMoneyToEscrow(3000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (에스크로 3000, 요청 5000)
            assertThatThrownBy(() -> walletService.releaseCompositeEscrow(member.getId(), 5000L, 0L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : deductCompositeEscrow - 에스크로 잔액 부족 시 InsufficientBalanceException")
        void deductCompositeEscrow_insufficientEscrow_shouldThrow() {
            // given
            wallet.moveMoneyToEscrow(3000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (에스크로 3000, 요청 5000)
            assertThatThrownBy(() -> walletService.deductCompositeEscrow(member.getId(), 5000L, 0L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }
    }

    @Nested
    @DisplayName("조회 메서드")
    class QueryTest {

        @Test
        @DisplayName("정상 : getMoneyBalance - 머니 잔액 조회")
        void getMoneyBalance_shouldReturnBalance() {
            // given
            Member member = MemberFixture.createMember(1L);
            Wallet wallet = WalletFixture.createEmptyWallet(member);
            wallet.depositMoney(10000L);

            given(walletRepository.findByMemberId(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            long balance = walletService.getMoneyBalance(member.getId());

            // then
            assertThat(balance).isEqualTo(10000L);
        }

        @Test
        @DisplayName("예외 : getMoneyBalance - 지갑 없으면 WalletNotFoundException")
        void getMoneyBalance_walletNotFound_shouldThrow() {
            // given
            given(walletRepository.findByMemberId(99L))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> walletService.getMoneyBalance(99L))
                    .isInstanceOf(WalletNotFoundException.class);
        }

        @Test
        @DisplayName("정상 : getWalletSummary - 이메일로 지갑 요약 조회")
        void getWalletSummary_shouldReturnSummary() {
            // given
            Member member = MemberFixture.createMember(1L);
            Wallet wallet = WalletFixture.createEmptyWallet(member);
            wallet.depositMoney(10000L);
            wallet.depositPoint(3000L);
            wallet.moveMoneyToEscrow(2000L);
            wallet.movePointToEscrow(1000L);

            given(walletRepository.findByMemberEmail("test@test.com"))
                    .willReturn(Optional.of(wallet));

            // when
            WalletSummaryResponse response = walletService.getWalletSummary("test@test.com");

            // then
            assertThat(response.moneyBalance()).isEqualTo(8000L);
            assertThat(response.pointBalance()).isEqualTo(2000L);
        }

        @Test
        @DisplayName("예외 : getWalletSummary - 지갑 없으면 WalletNotFoundException")
        void getWalletSummary_walletNotFound_shouldThrow() {
            // given
            given(walletRepository.findByMemberEmail("unknown@test.com"))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> walletService.getWalletSummary("unknown@test.com"))
                    .isInstanceOf(WalletNotFoundException.class);
        }
    }
}
