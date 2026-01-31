package com.ureca.snac.asset.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.ureca.snac.asset.dto.AssetHistoryListRequest;
import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.AssetType;
import com.ureca.snac.asset.entity.TransactionCategory;
import com.ureca.snac.asset.entity.TransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

import static com.ureca.snac.asset.entity.QAssetHistory.assetHistory;

@Slf4j
@Repository
@RequiredArgsConstructor
public class AssetHistoryRepositoryImpl implements AssetHistoryRepositoryCustom {

    private final JPAQueryFactory queryFactory;

    @Override
    public Slice<AssetHistory> findWithFilters(Long memberId, AssetHistoryListRequest request) {

        int pageSize = request.size();

        List<AssetHistory> histories = queryFactory
                .selectFrom(assetHistory)
                .where(
                        memberIdEq(memberId),
                        assetTypeEq(request.assetType()),
                        transactionTypeEq(request.transactionType()),
                        yearMonthEq(request.yearMonth()),
                        categoryEq(request.category()),
                        cursorCondition(request.cursor())
                )
                .orderBy(assetHistory.createdAt.desc(), assetHistory.id.desc())
                .limit(pageSize + 1) // 다음페이지 확인 위해
                .fetch();

        boolean hasNext = false;
        if (histories.size() > pageSize) {
            histories.remove(pageSize); // 응답 초과분 제거
            hasNext = true;
        }

        return new SliceImpl<>(histories, Pageable.unpaged(), hasNext);
    }

    private BooleanExpression memberIdEq(Long memberId) {
        return assetHistory.member.id.eq(memberId);
    }

    private BooleanExpression assetTypeEq(AssetType assetType) {
        return assetType != null ? assetHistory.assetType.eq(assetType) : null;
    }

    private BooleanExpression transactionTypeEq(TransactionType transactionType) {
        return transactionType != null ?
                assetHistory.transactionType.eq(transactionType) : null;
    }

    private BooleanExpression yearMonthEq(String yearMonth) {
        if (yearMonth == null || yearMonth.isBlank()) {
            return null;
        }
        return assetHistory.yearMonth.eq(yearMonth);
    }

    private BooleanExpression categoryEq(TransactionCategory category) {
        return category != null ? assetHistory.category.eq(category) : null;
    }

    private BooleanExpression cursorCondition(String cursorStr) {
        if (cursorStr == null || cursorStr.isBlank()) {
            return null;
        }
        try {
            String[] parts = cursorStr.trim().split(",");
            // StringTokenizer 는 레거시 코드라서 사용안함

            LocalDateTime cursorTime = LocalDateTime.parse(parts[0]);
            Long cursorId = Long.parseLong(parts[1]);

            return Expressions.booleanTemplate(
                    "({0}, {1}) < ({2}, {3})",
                    assetHistory.createdAt,
                    assetHistory.id,
                    cursorTime,
                    cursorId
            );
        } catch (RuntimeException e) {
            log.warn("[커서 파싱 오류] 잘못된 형식의 커서가 입력, 첫페이지로 조회. cursor : {}, cause : {}", cursorStr, e.getMessage());
            return null;
        }
    }
}
