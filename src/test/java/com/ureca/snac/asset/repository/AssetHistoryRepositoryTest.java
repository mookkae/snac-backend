package com.ureca.snac.asset.repository;

import com.ureca.snac.asset.dto.AssetHistoryListRequest;
import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.AssetType;
import com.ureca.snac.asset.entity.TransactionCategory;
import com.ureca.snac.asset.entity.TransactionType;
import com.ureca.snac.member.entity.Member;
import com.ureca.snac.member.repository.MemberRepository;
import com.ureca.snac.support.RepositoryTestSupport;
import com.ureca.snac.support.fixture.MemberFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Slice;

import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AssetHistoryRepository 테스트")
class AssetHistoryRepositoryTest extends RepositoryTestSupport {

    @Autowired
    private AssetHistoryRepository assetHistoryRepository;

    @Autowired
    private MemberRepository memberRepository;

    private Member member;

    @BeforeEach
    void setUpMember() {
        member = MemberFixture.builder()
                .id(null)
                .build();

        member = memberRepository.save(member);
    }

    @Nested
    @DisplayName("findWithFilters 메서드")
    class FindWithFiltersTest {

        @Test
        @DisplayName("성공 : memberId 조건만으로 조회")
        void findWithFilters_memberIdOnly_returnsMatchingRecords() {
            // given
            createAndSaveMoneyRecharge(10000L, 10000L, 1L);
            createAndSaveMoneyRecharge(5000L, 15000L, 2L);

            AssetHistoryListRequest request = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, null, null, 20
            );

            // when
            Slice<AssetHistory> result = assetHistoryRepository.findWithFilters(member.getId(), request);

            // then
            assertThat(result.getContent()).hasSize(2);
            assertThat(result.hasNext()).isFalse();
        }

        @Test
        @DisplayName("성공 : assetType 필터로 조회한다")
        void findWithFilters_withAssetTypeFilter_returnsFilteredRecords() {
            // given
            createAndSaveMoneyRecharge(10000L, 10000L, 1L);
            createAndSavePointBonus(1000L, 1000L);

            AssetHistoryListRequest moneyRequest = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, null, null, 20
            );
            AssetHistoryListRequest pointRequest = new AssetHistoryListRequest(
                    AssetType.POINT, null, null, null, null, 20
            );

            // when
            Slice<AssetHistory> moneyResult = assetHistoryRepository.findWithFilters(member.getId(), moneyRequest);
            Slice<AssetHistory> pointResult = assetHistoryRepository.findWithFilters(member.getId(), pointRequest);

            // then
            assertThat(moneyResult.getContent()).hasSize(1);
            assertThat(pointResult.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("성공 : transactionType 필터로 조회한다")
        void findWithFilters_withTransactionTypeFilter_returnsFilteredRecords() {
            // given
            createAndSaveMoneyRecharge(10000L, 10000L, 1L);
            createAndSaveTradeBuy(5000L, 5000L, 1L);

            AssetHistoryListRequest depositRequest = new AssetHistoryListRequest(
                    AssetType.MONEY, TransactionType.DEPOSIT, null, null, null, 20
            );
            AssetHistoryListRequest withdrawalRequest = new AssetHistoryListRequest(
                    AssetType.MONEY, TransactionType.WITHDRAWAL, null, null, null, 20
            );

            // when
            Slice<AssetHistory> depositResult = assetHistoryRepository.findWithFilters(member.getId(), depositRequest);
            Slice<AssetHistory> withdrawalResult = assetHistoryRepository.findWithFilters(member.getId(), withdrawalRequest);

            // then
            assertThat(depositResult.getContent()).hasSize(1);
            assertThat(withdrawalResult.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("성공 : yearMonth 필터로 조회한다")
        void findWithFilters_withYearMonthFilter_returnsFilteredRecords() {
            // given
            createAndSaveMoneyRecharge(10000L, 10000L, 1L);
            String currentYearMonth = java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM"));

            AssetHistoryListRequest request = new AssetHistoryListRequest(
                    AssetType.MONEY, null, currentYearMonth, null, null, 20
            );
            AssetHistoryListRequest wrongMonthRequest = new AssetHistoryListRequest(
                    AssetType.MONEY, null, "2000-01", null, null, 20
            );

            // when
            Slice<AssetHistory> result = assetHistoryRepository.findWithFilters(member.getId(), request);
            Slice<AssetHistory> emptyResult = assetHistoryRepository.findWithFilters(member.getId(), wrongMonthRequest);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(emptyResult.getContent()).isEmpty();
        }

        @Test
        @DisplayName("성공 : 커서 기반 페이지네이션이 동작한다")
        void findWithFilters_withCursor_returnsPaginatedResults() {
            // given
            createAndSaveMoneyRecharge(1000L, 1000L, 1L);
            createAndSaveMoneyRecharge(2000L, 3000L, 2L);
            createAndSaveMoneyRecharge(3000L, 6000L, 3L);

            AssetHistoryListRequest firstPageRequest = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, null, null, 2
            );

            // when
            Slice<AssetHistory> firstPage = assetHistoryRepository.findWithFilters(member.getId(), firstPageRequest);

            // then
            assertThat(firstPage.getContent()).hasSize(2);
            assertThat(firstPage.hasNext()).isTrue();

            // second page request
            AssetHistory lastOfFirstPage = firstPage.getContent().get(1);
            String cursor = lastOfFirstPage.getCreatedAt().toString() + "," + lastOfFirstPage.getId();

            AssetHistoryListRequest secondPageRequest = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, null, cursor, 2
            );

            Slice<AssetHistory> secondPage = assetHistoryRepository.findWithFilters(member.getId(), secondPageRequest);

            // then
            assertThat(secondPage.getContent()).hasSize(1);
            assertThat(secondPage.hasNext()).isFalse();
        }

        @Test
        @DisplayName("성공 : 빈 결과를 정상 처리한다")
        void findWithFilters_noData_returnsEmptySlice() {
            // given
            AssetHistoryListRequest request = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, null, null, 20
            );

            // when
            Slice<AssetHistory> result = assetHistoryRepository.findWithFilters(member.getId(), request);

            // then
            assertThat(result.getContent()).isEmpty();
            assertThat(result.hasNext()).isFalse();
        }

        @Test
        @DisplayName("성공 : 잘못된 커서 형식은 무시하고 첫 페이지를 반환한다")
        void findWithFilters_invalidCursorFormat_returnsFirstPage() {
            // given
            createAndSaveMoneyRecharge(10000L, 10000L, 1L);

            AssetHistoryListRequest request = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, null, "invalid_cursor_format", 20
            );

            // when
            Slice<AssetHistory> result = assetHistoryRepository.findWithFilters(member.getId(), request);

            // then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("성공 : Category 필터가 동작")
        void findWithFilters_withCategoryFilter_returnsFilteredRecords() {

            // given 1 : 충전 내역 (Category: RECHARGE) -> 조회되어야 함
            createAndSaveMoneyRecharge(10000L, 10000L, 1L);
            // given 2 : 구매 내역 (Category: BUY) -> 걸러져야 함
            createAndSaveTradeBuy(5000L, 5000L, 1L);

            AssetHistoryListRequest request = new AssetHistoryListRequest(
                    AssetType.MONEY, null, null, TransactionCategory.RECHARGE, null, 20);

            // when
            Slice<AssetHistory> result = assetHistoryRepository.findWithFilters(member.getId(), request);

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getCategory())
                    .isEqualTo(TransactionCategory.RECHARGE);
        }

        @Test
        @DisplayName("성공 : 조건이 공백이거나 Null이면 필터링 무시되면서 조회")
        void findWithFilters_ignoreNullOrBlankConditions() {
            // given
            createAndSaveMoneyRecharge(10000L, 10000L, 1L);

            // given 2 : isBlank 조건
            AssetHistoryListRequest request = new AssetHistoryListRequest(
                    AssetType.MONEY, null, "", null, "", 20);

            // when
            Slice<AssetHistory> result = assetHistoryRepository.findWithFilters(member.getId(), request);

            // then 필터 무시로 데이터가 정상 조회
            assertThat(result.getContent()).hasSize(1);
        }
    }

    @Test
    @DisplayName("성공 : Member 페치조인 없이 N + 1 검증")
    void checkNPlusOne() {
        // given : Member 1명, History 10개
        for (int i = 1; i <= 10; i++) {
            createAndSaveMoneyRecharge(1000L, 2000L, (long) i);
        }
        em.flush();
        em.clear();

        AssetHistoryListRequest request = new AssetHistoryListRequest(
                null, null, null, null, null, 20
        );
        System.out.println("조회 쿼리 시작");

        // when: 조회 수행 SELECT 쿼리가 1번
        Slice<AssetHistory> result = assetHistoryRepository.findWithFilters(member.getId(), request);

        System.out.println("조회 쿼리 1번 확인");

        // then : 데이터 개수 검증
        assertThat(result.getContent()).hasSize(10);

        // 실제 로직처럼 Member의 ID만 건드렸을 때 추가 쿼리가 안 나가는지 확인
        for (AssetHistory h : result.getContent()) {
            assertThat(h.getMember().getId()).isNotNull(); // FK 접근 (쿼리 안 나감)
//            h.getMember().getName(); // <-- 주석 해제 시 SELECT 쿼리가 나가면 정상 (지연 로딩)
        }
    }

    // 헬퍼 메소드

    private void createAndSaveMoneyRecharge(Long amount, Long balanceAfter, Long paymentId) {
        AssetHistory history = AssetHistory.createMoneyRecharge(member, paymentId, amount, balanceAfter);
        assetHistoryRepository.save(history);
    }

    private void createAndSavePointBonus(Long amount, Long balanceAfter) {
        AssetHistory history = AssetHistory.createSignupBonus(member, balanceAfter);
        assetHistoryRepository.save(history);
    }

    private void createAndSaveTradeBuy(Long amount, Long balanceAfter, Long tradeId) {
        AssetHistory history = AssetHistory.createTradeBuy(
                member, tradeId, "테스트 상품", AssetType.MONEY, amount, balanceAfter);
        assetHistoryRepository.save(history);
    }
}
