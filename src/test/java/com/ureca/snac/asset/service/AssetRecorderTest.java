package com.ureca.snac.asset.service;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.AssetType;
import com.ureca.snac.asset.entity.TransactionDetail;
import com.ureca.snac.asset.repository.AssetHistoryRepository;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@DisplayName("AssetRecorder 단위 테스트")
@ExtendWith(MockitoExtension.class)
class AssetRecorderTest {

    private AssetRecorderImpl assetRecorder;
    private SimpleMeterRegistry meterRegistry;

    @Mock
    private AssetHistoryRepository assetHistoryRepository;

    @Mock
    private MemberRepository memberRepository;

    @Captor
    private ArgumentCaptor<AssetHistory> captor;

    private Member member;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        assetRecorder = new AssetRecorderImpl(
                assetHistoryRepository, memberRepository, meterRegistry
        );
        member = MemberFixture.createMember(1L);

        lenient().when(memberRepository.findById(member.getId())).thenReturn(Optional.of(member));
    }

    @Nested
    @DisplayName("recordMoneyRecharge 메서드")
    class RecordMoneyRechargeTest {

        @Test
        @DisplayName("성공 : 머니 충전 내역을 저장")
        void recordMoneyRecharge_validInput_savesHistory() {
            // given
            Long paymentId = 100L;
            Long amount = 10000L;
            Long balanceAfter = 10000L;

            // when
            assetRecorder.recordMoneyRecharge(member.getId(), paymentId, amount, balanceAfter);

            // then
            verify(assetHistoryRepository).save(captor.capture());

            AssetHistory saved = captor.getValue();
            assertThat(saved.getMember()).isEqualTo(member);
            assertThat(saved.getTransactionDetail()).isNull();
            assertThat(saved.getAmount()).isEqualTo(amount);
        }
    }

    @Nested
    @DisplayName("recordMoneyRechargeCancel 메서드")
    class RecordMoneyRechargeCancelTest {

        @Test
        @DisplayName("성공 : 머니 충전 취소 내역을 저장")
        void recordMoneyRechargeCancel_validInput_savesHistory() {
            // given
            Long paymentId = 100L;
            Long amount = 10000L;
            Long balanceAfter = 0L;

            // when
            assetRecorder.recordMoneyRechargeCancel(member.getId(), paymentId, amount, balanceAfter);

            // then
            verify(assetHistoryRepository).save(captor.capture());

            AssetHistory saved = captor.getValue();
            assertThat(saved.getTransactionDetail()).isNull();
        }
    }

    @Nested
    @DisplayName("recordTradeBuy 메서드")
    class RecordTradeBuyTest {

        @Test
        @DisplayName("성공 : 머니로 구매한 내역을 저장")
        void recordTradeBuy_withMoney_savesWithMoneyAssetType() {
            // given
            Long tradeId = 200L;
            String title = "SKT 2GB";
            Long amount = 5000L;
            Long balanceAfter = 5000L;

            // when
            assetRecorder.recordTradeBuy(member.getId(), tradeId, title,
                    AssetType.MONEY, amount, balanceAfter);

            // then
            verify(assetHistoryRepository).save(captor.capture());

            AssetHistory saved = captor.getValue();
            assertThat(saved.getAssetType()).isEqualTo(AssetType.MONEY);
            assertThat(saved.getTransactionDetail()).isNull();
            assertThat(saved.getTitle()).isEqualTo(title);
        }

        @Test
        @DisplayName("성공 : 포인트로 구매한 내역을 저장")
        void recordTradeBuy_withPoint_savesWithPointAssetType() {
            // given
            Long tradeId = 200L;
            String title = "SKT 2GB";
            Long amount = 5000L;
            Long balanceAfter = 5000L;

            // when
            assetRecorder.recordTradeBuy(member.getId(), tradeId, title,
                    AssetType.POINT, amount, balanceAfter);

            // then
            verify(assetHistoryRepository).save(captor.capture());

            AssetHistory saved = captor.getValue();
            assertThat(saved.getAssetType()).isEqualTo(AssetType.POINT);
            assertThat(saved.getTransactionDetail()).isNull();
        }
    }

    @Nested
    @DisplayName("recordTradeSell 메서드")
    class RecordTradeSellTest {

        @Test
        @DisplayName("성공 : 판매 수익 내역을 저장")
        void recordTradeSell_validInput_savesHistory() {
            // given
            Long tradeId = 200L;
            String title = "SKT 2GB";
            Long amount = 5000L;
            Long balanceAfter = 15000L;

            // when
            assetRecorder.recordTradeSell(member.getId(), tradeId, title, amount, balanceAfter);

            // then
            verify(assetHistoryRepository).save(captor.capture());

            AssetHistory saved = captor.getValue();
            assertThat(saved.getTransactionDetail()).isNull();
            assertThat(saved.getTitle()).isEqualTo(title);
        }
    }

    @Nested
    @DisplayName("recordTradeCancelRefund 메서드")
    class RecordTradeCancelRefundTest {

        @Test
        @DisplayName("성공 : 머니 환불 내역을 저장")
        void recordTradeCancelRefund_withMoney_savesWithMoneyAssetType() {
            // given
            Long tradeId = 200L;
            String title = "SKT 2GB";
            Long amount = 5000L;
            Long balanceAfter = 10000L;

            // when
            assetRecorder.recordTradeCancelRefund(member.getId(), tradeId, title,
                    AssetType.MONEY, amount, balanceAfter);

            // then
            verify(assetHistoryRepository).save(captor.capture());

            AssetHistory saved = captor.getValue();
            assertThat(saved.getTransactionDetail()).isNull();
            assertThat(saved.getAssetType()).isEqualTo(AssetType.MONEY);
        }

        @Test
        @DisplayName("성공 : 포인트 환불 내역을 저장")
        void recordTradeCancelRefund_withPoint_savesWithPointAssetType() {
            // given
            Long tradeId = 200L;
            String title = "SKT 2GB";
            Long amount = 5000L;
            Long balanceAfter = 10000L;

            // when
            assetRecorder.recordTradeCancelRefund(member.getId(), tradeId, title,
                    AssetType.POINT, amount, balanceAfter);

            // then
            verify(assetHistoryRepository).save(captor.capture());

            AssetHistory saved = captor.getValue();
            assertThat(saved.getAssetType()).isEqualTo(AssetType.POINT);
        }
    }

    @Nested
    @DisplayName("recordSignupBonus 메서드")
    class RecordSignupBonusTest {

        @Test
        @DisplayName("성공 : 가입 보너스 내역을 저장")
        void recordSignupBonus_validInput_savesHistory() {
            // given
            Long amount = 1000L;
            Long balanceAfter = 1000L;

            // when
            assetRecorder.recordSignupBonus(member.getId(), amount, balanceAfter);

            // then
            verify(assetHistoryRepository).save(captor.capture());

            AssetHistory saved = captor.getValue();
            assertThat(saved.getTransactionDetail()).isEqualTo(TransactionDetail.SIGNUP_BONUS);
            assertThat(saved.getAssetType()).isEqualTo(AssetType.POINT);
        }
    }

    @Nested
    @DisplayName("recordTradeCompletionBonus 메서드")
    class RecordTradeCompletionBonusTest {

        @Test
        @DisplayName("성공 : 거래 완료 보너스 내역을 저장")
        void recordTradeCompletionBonus_validInput_savesHistory() {
            // given
            Long tradeId = 200L;
            Long amount = 100L;
            Long balanceAfter = 1100L;

            // when
            assetRecorder.recordTradeCompletionBonus(member.getId(), tradeId, amount, balanceAfter);

            // then
            verify(assetHistoryRepository).save(captor.capture());

            AssetHistory saved = captor.getValue();
            assertThat(saved.getTransactionDetail()).isEqualTo(TransactionDetail.TRADE_COMPLETION_BONUS);
        }
    }

    @Nested
    @DisplayName("recordSettlement 메서드")
    class RecordSettlementTest {

        @Test
        @DisplayName("성공 : 정산 내역을 저장")
        void recordSettlement_validInput_savesHistory() {
            // given
            Long settlementId = 300L;
            Long amount = 50000L;
            Long balanceAfter = 0L;

            // when
            assetRecorder.recordSettlement(member.getId(), settlementId, amount, balanceAfter);

            // then
            verify(assetHistoryRepository).save(captor.capture());

            AssetHistory saved = captor.getValue();
            assertThat(saved.getTransactionDetail()).isNull();
        }
    }

    @Nested
    @DisplayName("공통 예외 처리")
    class CommonExceptionTest {

        @Test
        @DisplayName("실패 : 회원이 없는 예외")
        void record_memberNotFound_throwsException() {
            // given
            Long nonExistentMemberId = 999L;

            // when, then
            assertThatThrownBy(() ->
                    assetRecorder.recordMoneyRecharge(nonExistentMemberId, 100L, 10000L, 10000L))
                    .isInstanceOf(MemberNotFoundException.class);

            verify(assetHistoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("멱등성 : 중복 멱등키는 문제")
        void record_duplicateIdempotencyKey_ignored() {
            // given
            Long paymentId = 100L;
            String idempotencyKey = "RECHARGE:" + paymentId;

            given(assetHistoryRepository.existsByIdempotencyKey(idempotencyKey)).willReturn(true);

            // when
            assetRecorder.recordMoneyRecharge(member.getId(), paymentId, 10000L, 10000L);

            // then
            verify(assetHistoryRepository, never()).save(any());

            // 메트릭 검증
            assertThat(meterRegistry.get("idempotency_duplicate_blocked_total")
                    .counter().count()).isEqualTo(1.0);
        }
    }
}
