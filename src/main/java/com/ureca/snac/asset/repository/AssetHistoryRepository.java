package com.ureca.snac.asset.repository;

import com.ureca.snac.asset.entity.AssetHistory;
import com.ureca.snac.asset.entity.TransactionDetail;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * JPA + Query DSL
 * findById, save나 findWithFilters 다 쓸 수 있다
 */
public interface AssetHistoryRepository extends JpaRepository<AssetHistory, Long>
//        AssetHistoryRepositoryCustom
{
    /**
     * 멱등성 검증으로 특정 회원이 특정 거래 세부 유형의 내역을 가지고 있는지 빠르게 확인
     *
     * @param memberId          회원 ID
     * @param transactionDetail 거래 세부 유형
     * @return 존재 여부
     */
    boolean existsByMemberIdAndTransactionDetail(Long memberId, TransactionDetail transactionDetail);
}
