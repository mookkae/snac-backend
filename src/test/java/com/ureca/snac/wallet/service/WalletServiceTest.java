package com.ureca.snac.wallet.service;

import com.ureca.snac.common.metric.TransactionAwareMetricRecorder;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import com.ureca.snac.support.fixture.WalletFixture;
import com.ureca.snac.wallet.dto.CompositeBalanceResult;
import com.ureca.snac.wallet.dto.WalletAvailableBalanceResponse;
import com.ureca.snac.wallet.entity.Wallet;
import com.ureca.snac.wallet.event.WalletCreatedEvent;
import com.ureca.snac.wallet.exception.InsufficientBalanceException;
import com.ureca.snac.wallet.exception.InvalidAmountException;
import com.ureca.snac.wallet.exception.WalletNotFoundException;
import com.ureca.snac.wallet.repository.WalletRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WalletService 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private WalletServiceImpl walletService;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TransactionAwareMetricRecorder metricRecorder;

    @BeforeEach
    void setUp() {
        walletService = new WalletServiceImpl(
                walletRepository,
                memberRepository,
                eventPublisher,
                metricRecorder
        );
    }

    @Nested
    @DisplayName("지갑 생성")
    class CreateWalletTest {

        @Test
        @DisplayName("멱등성 : 이미 지갑이 있으면 생성 안 함")
        void createWallet_Idempotent() {
            // given
            Long memberId = 1L;
            given(walletRepository.existsByMemberId(memberId))
                    .willReturn(true);

            // when
            walletService.createWallet(memberId);

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
            Long memberId = 1L;
            Member member = MemberFixture.createMember(memberId);
            given(walletRepository.existsByMemberId(memberId))
                    .willReturn(false);
            given(memberRepository.findById(memberId))
                    .willReturn(Optional.of(member));

            // when
            walletService.createWallet(memberId);

            // then
            verify(memberRepository).findById(memberId);
            verify(walletRepository).save(any());
            verify(eventPublisher).publishEvent(any(WalletCreatedEvent.class));
        }

        @Test
        @DisplayName("예외 : 회원이 없으면 MemberNotFoundException")
        void createWallet_MemberNotFound() {
            // given
            Long memberId = 999L;
            given(walletRepository.existsByMemberId(memberId))
                    .willReturn(false);
            given(memberRepository.findById(memberId))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> walletService.createWallet(memberId))
                    .isInstanceOf(MemberNotFoundException.class);

            verify(walletRepository, never()).save(any());
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
            wallet = WalletFixture.createWalletWithBalance(member, 10000L, 5000L);
        }

        @Test
        @DisplayName("정상 : depositMoney - 입금 후 잔액 반환")
        void depositMoney_shouldReturnFinalBalance() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            long finalBalance = walletService.depositMoney(member.getId(), 3000L);

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
            long finalBalance = walletService.withdrawMoney(member.getId(), 4000L);

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
            long finalBalance = walletService.depositPoint(member.getId(), 2000L);

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
            wallet = WalletFixture.createWalletWithBalance(member, 10000L, 5000L);
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
        @DisplayName("예외 : moveCompositeToEscrow - 머니는 충분하지만 포인트 잔액 부족 시 InsufficientBalanceException")
        void moveCompositeToEscrow_pointInsufficient_shouldThrow() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (머니 7000 OK, 포인트 8000 FAIL)
            assertThatThrownBy(() -> walletService.moveCompositeToEscrow(member.getId(), 7000L, 8000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("정상 : cancelCompositeEscrow - 머니+포인트 에스크로 복원")
        void cancelCompositeEscrow_shouldReleaseBoth() {
            // given
            wallet.moveCompositeToEscrow(10000L, 5000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.cancelCompositeEscrow(member.getId(), 6000L, 2000L);

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
            wallet.moveCompositeToEscrow(10000L, 5000L);
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
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then
            assertThatThrownBy(() -> walletService.moveCompositeToEscrow(member.getId(), 0L, 0L))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("예외 : moveCompositeToEscrow - 머니 음수이면 InvalidAmountException")
        void moveCompositeToEscrow_negativeMoneyAmount_shouldThrow() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then
            assertThatThrownBy(() -> walletService.moveCompositeToEscrow(member.getId(), -1000L, 1000L))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("예외 : moveCompositeToEscrow - 포인트 음수이면 InvalidAmountException")
        void moveCompositeToEscrow_negativePointAmount_shouldThrow() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then
            assertThatThrownBy(() -> walletService.moveCompositeToEscrow(member.getId(), 1000L, -1000L))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("정상 : cancelCompositeEscrow - 머니만 복원 (포인트=0)")
        void cancelCompositeEscrow_moneyOnly_shouldReleaseMoneyOnly() {
            // given
            wallet.moveCompositeToEscrow(10000L, 0L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.cancelCompositeEscrow(member.getId(), 5000L, 0L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(5000L);
            assertThat(result.moneyEscrow()).isEqualTo(5000L);
            assertThat(result.pointBalance()).isEqualTo(5000L);
            assertThat(result.pointEscrow()).isEqualTo(0L);
        }

        @Test
        @DisplayName("정상 : cancelCompositeEscrow - 포인트만 복원 (머니=0)")
        void cancelCompositeEscrow_pointOnly_shouldReleasePointOnly() {
            // given
            wallet.moveCompositeToEscrow(0L, 5000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            CompositeBalanceResult result = walletService.cancelCompositeEscrow(member.getId(), 0L, 3000L);

            // then
            assertThat(result.moneyBalance()).isEqualTo(10000L);
            assertThat(result.moneyEscrow()).isEqualTo(0L);
            assertThat(result.pointBalance()).isEqualTo(3000L);
            assertThat(result.pointEscrow()).isEqualTo(2000L);
        }

        @Test
        @DisplayName("예외 : cancelCompositeEscrow - 둘 다 0 이하이면 InvalidAmountException")
        void cancelCompositeEscrow_bothZero_shouldThrow() {
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then
            assertThatThrownBy(() -> walletService.cancelCompositeEscrow(member.getId(), 0L, 0L))
                    .isInstanceOf(InvalidAmountException.class);
        }

        @Test
        @DisplayName("정상 : deductCompositeEscrow - 머니만 차감 (포인트=0)")
        void deductCompositeEscrow_moneyOnly_shouldDeductMoneyOnly() {
            // given
            wallet.moveCompositeToEscrow(10000L, 0L);
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
            wallet.moveCompositeToEscrow(0L, 5000L);
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
            // given
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

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
        @DisplayName("예외 : cancelCompositeEscrow - 에스크로 잔액 부족 시 InsufficientBalanceException")
        void cancelCompositeEscrow_insufficientEscrow_shouldThrow() {
            // given
            wallet.moveCompositeToEscrow(3000L, 0L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (에스크로 3000, 요청 5000)
            assertThatThrownBy(() -> walletService.cancelCompositeEscrow(member.getId(), 5000L, 0L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : cancelCompositeEscrow - 포인트 에스크로 잔액 부족 시 InsufficientBalanceException")
        void cancelCompositeEscrow_insufficientPointEscrow_shouldThrow() {
            // given
            wallet.moveCompositeToEscrow(0L, 2000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (포인트 에스크로 2000, 요청 3000)
            assertThatThrownBy(() -> walletService.cancelCompositeEscrow(member.getId(), 0L, 3000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : deductCompositeEscrow - 에스크로 잔액 부족 시 InsufficientBalanceException")
        void deductCompositeEscrow_insufficientEscrow_shouldThrow() {
            // given
            wallet.moveCompositeToEscrow(3000L, 0L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (에스크로 3000, 요청 5000)
            assertThatThrownBy(() -> walletService.deductCompositeEscrow(member.getId(), 5000L, 0L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }

        @Test
        @DisplayName("예외 : deductCompositeEscrow - 포인트 에스크로 잔액 부족 시 InsufficientBalanceException")
        void deductCompositeEscrow_insufficientPointEscrow_shouldThrow() {
            // given
            wallet.moveCompositeToEscrow(0L, 2000L);
            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (포인트 에스크로 2000, 요청 3000)
            assertThatThrownBy(() -> walletService.deductCompositeEscrow(member.getId(), 0L, 3000L))
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
            Wallet wallet = WalletFixture.createWalletWithBalance(member, 10000L, 0L);

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
        @DisplayName("정상 : getWalletSummary - 지갑 요약 조회")
        void getWalletSummary_shouldReturnSummary() {
            // given
            Member member = MemberFixture.createMember(1L);
            Wallet wallet = WalletFixture.createWalletWithBalance(member, 10000L, 3000L);
            wallet.moveCompositeToEscrow(2000L, 1000L);

            given(walletRepository.findByMemberId(1L))
                    .willReturn(Optional.of(wallet));

            // when
            WalletAvailableBalanceResponse response = walletService.getWalletSummary(1L);

            // then
            assertThat(response.moneyBalance()).isEqualTo(8000L);
            assertThat(response.pointBalance()).isEqualTo(2000L);
        }

        @Test
        @DisplayName("예외 : getWalletSummary - 지갑 없으면 WalletNotFoundException")
        void getWalletSummary_walletNotFound_shouldThrow() {
            // given
            given(walletRepository.findByMemberId(999L))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> walletService.getWalletSummary(999L))
                    .isInstanceOf(WalletNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("freezeMoney")
    class FreezeMoneyTest {

        @Test
        @DisplayName("정상 : balance 감소, frozen 증가, freeze 후 balance 반환")
        void freezeMoney_shouldFreezeAndReturnBalance() {
            // given
            Member member = MemberFixture.createMember(1L);
            Wallet wallet = WalletFixture.createWalletWithBalance(member, 10000L, 0L);

            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            long balanceAfter = walletService.freezeMoney(member.getId(), 6000L);

            // then
            assertThat(balanceAfter).isEqualTo(4000L);
        }

        @Test
        @DisplayName("예외 : 잔액 부족 시 InsufficientBalanceException")
        void freezeMoney_insufficientBalance_shouldThrow() {
            // given
            Member member = MemberFixture.createMember(1L);
            Wallet wallet = WalletFixture.createWalletWithBalance(member, 5000L, 0L);

            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then
            assertThatThrownBy(() -> walletService.freezeMoney(member.getId(), 10000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }
    }

    @Nested
    @DisplayName("unfreezeMoney")
    class UnfreezeMoneyTest {

        @Test
        @DisplayName("정상 : frozen 감소, balance 증가, unfreeze 후 balance 반환")
        void unfreezeMoney_shouldUnfreezeAndReturnBalance() {
            // given
            Member member = MemberFixture.createMember(1L);
            Wallet wallet = WalletFixture.createWalletWithBalance(member, 10000L, 0L);
            wallet.freezeMoney(10000L); // balance=0, frozen=10000

            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            long balanceAfter = walletService.unfreezeMoney(member.getId(), 10000L);

            // then
            assertThat(balanceAfter).isEqualTo(10000L);
        }

        @Test
        @DisplayName("예외 : frozen 부족 시 InsufficientBalanceException")
        void unfreezeMoney_insufficientFrozen_shouldThrow() {
            // given
            Member member = MemberFixture.createMember(1L);
            Wallet wallet = WalletFixture.createWalletWithBalance(member, 10000L, 0L);
            wallet.freezeMoney(5000L); // balance=5000, frozen=5000

            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (frozen=5000, 요청=10000)
            assertThatThrownBy(() -> walletService.unfreezeMoney(member.getId(), 10000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }
    }

    @Nested
    @DisplayName("deductFrozenMoney")
    class DeductFrozenMoneyTest {

        @Test
        @DisplayName("정상 : frozen 차감 후 실제 사용 가능 balance 반환")
        void deductFrozenMoney_shouldDeductAndReturnBalance() {
            // given
            Member member = MemberFixture.createMember(1L);
            Wallet wallet = WalletFixture.createWalletWithBalance(member, 10000L, 0L);
            wallet.freezeMoney(10000L); // balance=0, frozen=10000

            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            long balanceAfter = walletService.deductFrozenMoney(member.getId(), 10000L);

            // then
            assertThat(balanceAfter).isEqualTo(0L); // balance 반환 (frozen 잔액 아님)
        }

        @Test
        @DisplayName("정상 : 다른 잔액이 있을 때 frozen 차감 후 실제 balance 반환 (balanceAfter 버그 수정 검증)")
        void deductFrozenMoney_withOtherBalance_shouldReturnActualBalance() {
            // given: 충전 10000 후 freeze, 이후 별도 5000 추가 입금 (다른 잔액 존재)
            Member member = MemberFixture.createMember(1L);
            Wallet wallet = WalletFixture.createWalletWithBalance(member, 10000L, 0L);
            wallet.freezeMoney(10000L);    // balance=0, frozen=10000
            wallet.depositMoney(5000L);    // balance=5000, frozen=10000 (다른 출처 잔액)

            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when
            long balanceAfter = walletService.deductFrozenMoney(member.getId(), 10000L);

            // then: frozen 제거 후 실제 사용 가능 balance = 5000 (frozenAfter=0 아님)
            assertThat(balanceAfter).isEqualTo(5000L);
        }

        @Test
        @DisplayName("예외 : frozen 부족 시 InsufficientBalanceException")
        void deductFrozenMoney_insufficientFrozen_shouldThrow() {
            // given
            Member member = MemberFixture.createMember(1L);
            Wallet wallet = WalletFixture.createWalletWithBalance(member, 10000L, 0L);
            wallet.freezeMoney(5000L); // balance=5000, frozen=5000

            given(walletRepository.findByMemberIdWithLock(member.getId()))
                    .willReturn(Optional.of(wallet));

            // when & then (frozen=5000, 요청=10000)
            assertThatThrownBy(() -> walletService.deductFrozenMoney(member.getId(), 10000L))
                    .isInstanceOf(InsufficientBalanceException.class);
        }
    }
}
