package com.ureca.snac.asset.repository;

import com.ureca.snac.asset.entity.AssetHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetHistoryRepository extends JpaRepository<AssetHistory, Long>,
        AssetHistoryRepositoryCustom {

    // 멱등키로 이미 처리된 요청인지 확인
    boolean existsByIdempotencyKey(String idempotencyKey);
}
