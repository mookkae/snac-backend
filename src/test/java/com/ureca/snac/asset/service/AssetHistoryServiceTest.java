package com.ureca.snac.asset.service;

import com.ureca.snac.asset.dto.AssetHistoryListRequest;
import com.ureca.snac.asset.dto.AssetHistoryResponse;
import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.AssetType;
import com.ureca.snac.asset.entity.TransactionCategory;
import com.ureca.snac.asset.fixture.AssetHistoryFixture;
import com.ureca.snac.asset.repository.AssetHistoryRepository;
import com.ureca.snac.common.CursorResult;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.exception.MemberNotFoundException;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.payment.entity.Payment;
import com.ureca.snac.payment.repository.PaymentRepository;
import com.ureca.snac.support.fixture.MemberFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.SliceImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@DisplayName("AssetHistoryService 단위 테스트")
@ExtendWith(MockitoExtension.class)
class AssetHistoryServiceTest {

    @InjectMocks
    private AssetHistoryServiceImpl assetHistoryService;

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AssetHistoryRepository assetHistoryRepository;

    @Mock
    private PaymentRepository paymentRepository;

    private Member member;
    private AssetHistoryListRequest request;

    @BeforeEach
    void setUp() {
        member = MemberFixture.createMember(1L);

        request = new AssetHistoryListRequest(
                AssetType.MONEY, null, null, null, null, 20
        );

        lenient().when(memberRepository.findByEmail(member.getEmail())).thenReturn(Optional.of(member));
    }

    @Nested
    @DisplayName("getAssetHistories 메서드")
    class GetAssetHistoriesTest {

        @Test
        @DisplayName("성공 : 자산 내역을 조회")
        void getAssetHistories_success() {
            // given
            AssetHistory history = createHistoryWithId(100L, 10000L, 10000L, 1L);

            given(assetHistoryRepository.findWithFilters(eq(member.getId()), any()))
                    .willReturn(new SliceImpl<>(List.of(history)));
            given(paymentRepository.findAllById(any())).willReturn(List.of());

            // when
            CursorResult<AssetHistoryResponse> result = assetHistoryService.getAssetHistories(member.getEmail(), request);

            // then
            assertThat(result.contents()).hasSize(1);
            assertThat(result.hasNext()).isFalse();
        }

        @Test
        @DisplayName("성공 : 빈 결과 처리")
        void getAssetHistories_emptyResult() {
            // given
            given(assetHistoryRepository.findWithFilters(eq(member.getId()), any()))
                    .willReturn(new SliceImpl<>(List.of()));

            // when
            CursorResult<AssetHistoryResponse> result = assetHistoryService.getAssetHistories(member.getEmail(), request);

            // then
            assertThat(result.contents()).isEmpty();
            assertThat(result.hasNext()).isFalse();
            assertThat(result.nextCursor()).isNull();
        }

        @Test
        @DisplayName("성공 : 다음 페이지 커서를 계산")
        void getAssetHistories_calculatesNextCursor() {
            // given
            AssetHistoryListRequest pagedRequest = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, null, null, 1
            );

            AssetHistory history = createHistoryWithId(100L, 10000L, 10000L, 1L);

            given(assetHistoryRepository.findWithFilters(eq(member.getId()), any()))
                    .willReturn(new SliceImpl<>(List.of(history), org.springframework.data.domain.Pageable.unpaged(), true));
            given(paymentRepository.findAllById(any())).willReturn(List.of());

            // when
            CursorResult<AssetHistoryResponse> result = assetHistoryService.getAssetHistories(member.getEmail(), pagedRequest);

            // then
            assertThat(result.hasNext()).isTrue();
            assertThat(result.nextCursor()).isNotNull();
        }

        @Test
        @DisplayName("성공 : RECHARGE 카테고리에 paymentKey를 매핑")
        void getAssetHistories_mapsPaymentKey() {
            // given
            Long paymentId = 100L;
            String paymentKey = "toss_payment_key_123";

            AssetHistoryListRequest rechargeRequest = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, TransactionCategory.RECHARGE, null, 20
            );

            AssetHistory history = createHistoryWithId(paymentId, 10000L, 10000L, 1L);

            Payment payment = mock(Payment.class);
            given(payment.getId()).willReturn(paymentId);
            given(payment.getPaymentKey()).willReturn(paymentKey);

            given(assetHistoryRepository.findWithFilters(eq(member.getId()), any()))
                    .willReturn(new SliceImpl<>(List.of(history)));
            given(paymentRepository.findAllById(List.of(paymentId))).willReturn(List.of(payment));

            // when
            CursorResult<AssetHistoryResponse> result = assetHistoryService.getAssetHistories(member.getEmail(), rechargeRequest);

            // then
            assertThat(result.contents().get(0).paymentKey()).isEqualTo(paymentKey);
        }

        @Test
        @DisplayName("성공 : RECHARGE가 아닌 카테고리는 paymentKey가 null")
        void getAssetHistories_nonRechargeCategory_paymentKeyIsNull() {
            // given
            AssetHistoryListRequest listRequest = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, TransactionCategory.BUY, null, 20
            );

            AssetHistory history = AssetHistoryFixture.withCreatedAt(
                    AssetHistoryFixture.withId(AssetHistoryFixture.createTradeBuyMoney(member), 1L),
                    LocalDateTime.now()
            );

            given(assetHistoryRepository.findWithFilters(eq(member.getId()), any()))
                    .willReturn(new SliceImpl<>(List.of(history)));

            // when
            CursorResult<AssetHistoryResponse> result = assetHistoryService.getAssetHistories(member.getEmail(), listRequest);

            // then
            assertThat(result.contents().get(0).paymentKey()).isNull();
        }

        @Test
        @DisplayName("성공 : Payment가 없으면 paymentKey가 null")
        void getAssetHistories_paymentNotFound_paymentKeyIsNull() {
            // given
            Long paymentId = 100L;

            AssetHistoryListRequest rechargeRequest = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, TransactionCategory.RECHARGE, null, 20
            );

            AssetHistory history = createHistoryWithId(paymentId, 10000L, 10000L, 1L);

            given(assetHistoryRepository.findWithFilters(eq(member.getId()), any()))
                    .willReturn(new SliceImpl<>(List.of(history)));
            given(paymentRepository.findAllById(List.of(paymentId))).willReturn(List.of());

            // when
            CursorResult<AssetHistoryResponse> result = assetHistoryService.getAssetHistories(member.getEmail(), rechargeRequest);

            // then
            assertThat(result.contents().get(0).paymentKey()).isNull();
        }

        @Test
        @DisplayName("실패 : 회원 예외 발생")
        void getAssetHistories_memberNotFound() {
            // given
            String email = "notfound@snac.com";

            given(memberRepository.findByEmail(email)).willReturn(Optional.empty());

            // when, then
            assertThatThrownBy(() -> assetHistoryService.getAssetHistories(email, request))
                    .isInstanceOf(MemberNotFoundException.class);
        }

        private AssetHistory createHistoryWithId(Long paymentId, Long amount, Long balanceAfter, Long id) {
            return AssetHistoryFixture.withCreatedAt(
                    AssetHistoryFixture.withId(
                            AssetHistoryFixture.createMoneyRecharge(member, paymentId, amount, balanceAfter), id),
                    LocalDateTime.now()
            );
        }
    }
}
