package com.ureca.snac.asset.entity;

import com.ureca.snac.asset.exception.*;
import com.ureca.snac.common.BaseTimeEntity;
import com.ureca.snac.member.entity.Member;
import jakarta.persistence.*;
import lombok.*;

import java.time.format.DateTimeFormatter;

/**
 * 사용자의 자산( 스낵 머니, 포인트 ) 내역 기록 엔티티
 * 읽기 전용 모델로 설계
 * 빠른 조회를 위해 비정규화 적용
 * 생성 시점의 데이터 유효성 검증
 * 무결성 보장
 */
@Entity
@Table(name = "asset_history",
        indexes = {
                // 월별 조회
                @Index(name = "idx_asset_history_member_asset_ym_created",
                        columnList = "member_id, asset_type, tx_year_month, created_at DESC"),
                // 전체 조회 보조인덱스
                @Index(name = "idx_asset_history_member_asset_created",
                        columnList = "member_id, asset_type, created_at DESC"),
                // 특정회원 보너스 지급 여부 확인 멱등성 체크
                @Index(name = "idx_asset_history_member_detail",
                        columnList = "member_id, transaction_detail")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(access = AccessLevel.PRIVATE)
public class AssetHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "asset_history_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Enumerated(EnumType.STRING)
    @Column(name = "asset_type", nullable = false, length = 10)
    private AssetType assetType;  // MONEY, POINT

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 12)
    private TransactionType transactionType; // DEPOSIT 음수 , WITHDRAWAL 양수

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 10)
    private TransactionCategory category;  // CHARGE, PAYMENT

    @Column(nullable = false)
    private Long amount; // 가격

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_detail", nullable = false, length = 20)
    private TransactionDetail transactionDetail;

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Column(nullable = false, length = 50)
    private String title; // 거래대상 제목

    @Column(name = "source_domain", nullable = false, length = 20)
    private SourceDomain sourceDomain; // CHARGE, TRADE, EVENT

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "tx_year_month", nullable = false, length = 10, updatable = false)
    private String yearMonth; // 비정규화 색인 필드 인덱스 사용

    public static AssetHistory create(
            Member member,
            AssetType assetType,
            TransactionType transactionType,
            TransactionCategory category,
            TransactionDetail transactionDetail,
            Long amount,
            Long balanceAfter,
            SourceDomain sourceDomain,
            Long sourceId) {

        validateCreateRequest(member, assetType, transactionType, category,
                transactionDetail, amount, balanceAfter, sourceDomain, sourceId);

        return AssetHistory.builder()
                .member(member)
                .assetType(assetType)
                .transactionType(transactionType)
                .category(category)
                .transactionDetail(transactionDetail)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .title(transactionDetail.getDisplayName())
                .sourceDomain(sourceDomain)
                .sourceId(sourceId)
                .build();
    }

    /**
     * 회원가입 축하 포인트 내역 생성 전용 팩토리 메서드
     * 고정값을 내부에서 처리하여 오류 방지
     *
     * @param member       회원
     * @param amount       포인트 금액
     * @param balanceAfter 지급 후 잔액
     * @return AssetHistory
     */
    public static AssetHistory createSignupBonus(
            Member member,
            Long amount,
            Long balanceAfter
    ) {
        return create(
                member,
                AssetType.POINT,
                TransactionType.DEPOSIT,
                TransactionCategory.EVENT,
                TransactionDetail.SIGNUP_BONUS,
                amount,
                balanceAfter,
                SourceDomain.EVENT,
                member.getId()
        );
    }

    // 생성 시점의 조건 검증
    private static void validateCreateRequest(
            Member member, AssetType assetType, TransactionType transactionType,
            TransactionCategory category, TransactionDetail transactionDetail,
            Long amount, Long balanceAfter, SourceDomain sourceDomain, Long sourceId) {

        if (member == null) {
            throw new InvalidAssetSourceException();
        }
        if (transactionDetail == null) {
            throw new InvalidAssetSourceException();
        }
        if (amount == null || amount <= 0) {
            throw new InvalidAssetAmountException();
        }
        if (balanceAfter == null || balanceAfter < 0) {
            throw new InvalidAssetBalanceException();
        }
        if (sourceDomain == null || sourceId == null || sourceId <= 0) {
            throw new InvalidAssetSourceException();
        }

        // 개선 비즈니스 규칙 검증 Enum 에게 위임
        if (!category.isValidFor(assetType)) {
            throw new InvalidAssetCategoryCombinationException();
        }
        if (!category.isConsistentWith(transactionType)) {
            throw new InconsistentTransactionTypeException();
        }
    }

    @PrePersist
    public void setYearMonth() {
        this.yearMonth = getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    public String getSignedAmountString() {
        String sign = this.transactionType == TransactionType.DEPOSIT ? "+" : "-";
        return sign + String.format("%,d", this.amount);
    }
}