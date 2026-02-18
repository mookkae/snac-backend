package com.ureca.snac.asset.entity;

import com.ureca.snac.asset.exception.*;
import com.ureca.snac.common.BaseTimeEntity;
import com.ureca.snac.member.entity.Member;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// 사용자의 자산( 스낵 머니, 포인트 ) 내역 기록 엔티티
@Entity
@Table(name = "asset_history",
        indexes = {
                // 월별 조회
                @Index(name = "idx_asset_history_member_asset_ym_created",
                        columnList = "member_id, asset_type, tx_year_month, created_at DESC"),
                // 전체 조회 (커서 기반 페이지네이션 — created_at DESC, id DESC 정렬 커버)
                @Index(name = "idx_asset_history_member_asset_created_id",
                        columnList = "member_id, asset_type, created_at DESC, asset_history_id DESC")
        },
        // 멱등키 유니크 제약
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_asset_history_idempotency_key",
                        columnNames = {"idempotency_key"}
                )
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
    @Column(name = "transaction_detail", length = 30)
    private TransactionDetail transactionDetail; // 세부적인 이벤트 종류 및 지원금액

    @Column(name = "balance_after", nullable = false)
    private Long balanceAfter;

    @Column(nullable = false, length = 50)
    private String title; // 거래대상 제목

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "tx_year_month", nullable = false, length = 10, updatable = false)
    private String yearMonth; // 비정규화 색인 필드 인덱스 사용

    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    // 멱등키 생성 - 동일 이벤트 중복 처리 방지
    public static String generateIdempotencyKey(String prefix, Object... ids) {
        StringBuilder sb = new StringBuilder(prefix);
        for (Object id : ids) {
            sb.append(":").append(id);
        }
        return sb.toString();
    }

    // 회원가입 축하 포인트 내역 기록
    public static AssetHistory createSignupBonus(
            Member member,
            Long balanceAfter
    ) {
        TransactionDetail detail = TransactionDetail.SIGNUP_BONUS;
        Long amount = detail.getDefaultAmount();

        return create(
                member,
                AssetType.POINT,
                TransactionType.DEPOSIT,
                TransactionCategory.EVENT,
                detail,
                amount,
                balanceAfter,
                detail.getDisplayName(),
                member.getId(),
                generateIdempotencyKey(detail.name(), member.getId())
        );
    }

    // 머니 충전 내역 기록
    public static AssetHistory createMoneyRecharge(
            Member member,
            Long paymentId,
            Long amount,
            Long balanceAfter
    ) {
        TransactionCategory category = TransactionCategory.RECHARGE;

        return create(
                member,
                AssetType.MONEY,
                TransactionType.DEPOSIT,
                category,
                null,
                amount,
                balanceAfter,
                category.getDisplayName(),
                paymentId,
                generateIdempotencyKey(category.name(), paymentId)
        );
    }

    // 머니 충전 취소 내역 기록
    public static AssetHistory createMoneyRechargeCancel(
            Member member,
            Long paymentId,
            Long amount,
            Long balanceAfter
    ) {
        TransactionCategory category = TransactionCategory.RECHARGE_CANCEL;

        return create(
                member,
                AssetType.MONEY,
                TransactionType.WITHDRAWAL,
                category,
                null,
                amount,
                balanceAfter,
                category.getDisplayName(),
                paymentId,
                generateIdempotencyKey(category.name(), paymentId)
        );
    }

    // 거래 구매 내역 기록
    public static AssetHistory createTradeBuy(
            Member member,
            Long tradeId,
            String title,
            AssetType assetType,
            Long amount,
            Long balanceAfter
    ) {
        TransactionCategory category =
                (assetType == AssetType.MONEY) ? TransactionCategory.BUY : TransactionCategory.POINT_USAGE;

        return create(
                member,
                assetType,
                TransactionType.WITHDRAWAL,
                category,
                null,
                amount,
                balanceAfter,
                title,
                tradeId,
                generateIdempotencyKey(category.name(), tradeId, assetType.name())
        );
    }

    // 거래 판매 수익 내역 기록
    public static AssetHistory createTradeSell(
            Member member,
            Long tradeId,
            String title,
            Long amount,
            Long balanceAfter
    ) {
        TransactionCategory category = TransactionCategory.SELL;

        return create(
                member,
                AssetType.MONEY,
                TransactionType.DEPOSIT,
                category,
                null,
                amount,
                balanceAfter,
                title,
                tradeId,
                generateIdempotencyKey(category.name(), tradeId)
        );
    }

    // 거래 취소 환불 내역 기록
    public static AssetHistory createTradeCancelRefund(
            Member member,
            Long tradeId,
            String title,
            AssetType assetType,
            Long amount,
            Long balanceAfter
    ) {
        TransactionCategory category = TransactionCategory.TRADE_CANCEL;

        return create(
                member,
                assetType,
                TransactionType.DEPOSIT,
                category,
                null,
                amount,
                balanceAfter,
                title,
                tradeId,
                generateIdempotencyKey(category.name(), tradeId, assetType.name())
        );
    }

    // 정산 출금 내역 기록
    public static AssetHistory createSettlement(
            Member member,
            Long settlementId,
            Long amount,
            Long balanceAfter
    ) {
        TransactionCategory category = TransactionCategory.SETTLEMENT;

        return create(
                member,
                AssetType.MONEY,
                TransactionType.WITHDRAWAL,
                category,
                null,
                amount,
                balanceAfter,
                category.getDisplayName(),
                settlementId,
                generateIdempotencyKey(category.name(), settlementId)
        );
    }

    // 거래 완료 보너스 포인트 내역 기록
    public static AssetHistory createTradeCompletionBonus(
            Member member,
            Long tradeId,
            Long balanceAfter
    ) {
        TransactionDetail detail = TransactionDetail.TRADE_COMPLETION_BONUS;
        Long amount = detail.getDefaultAmount();

        return create(
                member,
                AssetType.POINT,
                TransactionType.DEPOSIT,
                TransactionCategory.EVENT,
                detail,
                amount,
                balanceAfter,
                detail.getDisplayName(),
                tradeId,
                generateIdempotencyKey(detail.name(), tradeId, member.getId())
        );
    }

    // 관리자/개발용 포인트 지급(외부 멱등성 id 주입) 내역 기록
    public static AssetHistory createAdminPointGrant(
            Member member,
            Long grantId,
            Long amount,
            Long balanceAfter,
            String reason
    ) {
        TransactionDetail detail = TransactionDetail.ADMIN_POINT_GRANT;

        return create(
                member,
                AssetType.POINT,
                TransactionType.DEPOSIT,
                TransactionCategory.EVENT,
                detail,
                amount,
                balanceAfter,
                reason,
                grantId,
                generateIdempotencyKey(detail.name(), grantId)
        );
    }

    private static AssetHistory create(
            Member member,
            AssetType assetType,
            TransactionType transactionType,
            TransactionCategory category,
            TransactionDetail transactionDetail,
            Long amount,
            Long balanceAfter,
            String title,
            Long sourceId,
            String idempotencyKey
    ) {
        validateCreateRequest(member, assetType, transactionType, category,
                amount, balanceAfter, sourceId, idempotencyKey);

        return AssetHistory.builder()
                .member(member)
                .assetType(assetType)
                .transactionType(transactionType)
                .category(category)
                .transactionDetail(transactionDetail)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .title(title)
                .sourceId(sourceId)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    // 생성 시점의 조건 검증
    private static void validateCreateRequest(
            Member member, AssetType assetType, TransactionType transactionType,
            TransactionCategory category, Long amount, Long balanceAfter,
            Long sourceId, String idempotencyKey) {

        if (member == null) {
            throw new InvalidAssetSourceException("멤버가 없다.");
        }
        // transactionDetail은 이벤트성 거래에만 사용, nullable 허용
        if (amount == null || amount <= 0) {
            throw new InvalidAssetAmountException();
        }
        if (balanceAfter == null || balanceAfter < 0) {
            throw new InvalidAssetBalanceException();
        }
        if (sourceId == null || sourceId <= 0) {
            throw new InvalidAssetSourceException("Source ID 오류");
        }
        // 멱등키 검증
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new InvalidAssetSourceException("멱등키 누락");
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
        // createdAt은 AuditingEntityListener가 설정하므로 아직 null일 수 있음
        // 현재 시간 기준으로 yearMonth 설정
        this.yearMonth = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }
}