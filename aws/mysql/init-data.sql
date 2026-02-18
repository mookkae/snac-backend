-- =============================================================
-- 부하 테스트용 더미 데이터 생성 스크립트
-- 대상: member 1명, wallet 1건, asset_history 100만 건
-- 실행 시간: 약 1~2분 (INSERT ... SELECT 더블링)
-- =============================================================

-- ─── 1. loadtest 회원 ───
-- password = BCrypt("Test1234")
INSERT INTO member (email, password, name, nickname, phone, birth_date, rating_score, role, activated, created_at, updated_at)
VALUES (
    'loadtest@test.com',
    '$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG',
    '부하테스터',
    'loadtester',
    '01000000000',
    '1990-01-01',
    0,
    'USER',
    'NORMAL',
    NOW(),
    NOW()
);

SET @MEMBER_ID = LAST_INSERT_ID();

-- ─── 2. 지갑 ───
INSERT INTO wallet (member_id, balance_money, balance_escrow_money, balance_point, balance_escrow_point, created_at, updated_at)
VALUES (@MEMBER_ID, 10000000, 0, 1000, 0, NOW(), NOW());

-- ─── 3. asset_history 100만 건 (더블링 방식) ───

-- 시드 데이터 20건 (다양한 카테고리 분포)
INSERT INTO asset_history
    (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
VALUES
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'RECHARGE',       NULL,                   50000, 50000,    '충전',        1,  '2025-01', CONCAT('SEED:', UUID()), '2025-01-05 10:00:00', '2025-01-05 10:00:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'RECHARGE',       NULL,                   30000, 80000,    '충전',        2,  '2025-01', CONCAT('SEED:', UUID()), '2025-01-12 14:30:00', '2025-01-12 14:30:00'),
    (@MEMBER_ID, 'MONEY', 'WITHDRAWAL',  'BUY',            NULL,                   15000, 65000,    'SKT 5GB 구매', 3,  '2025-02', CONCAT('SEED:', UUID()), '2025-02-03 09:20:00', '2025-02-03 09:20:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'SELL',           NULL,                   20000, 85000,    'KT 10GB 판매', 4,  '2025-02', CONCAT('SEED:', UUID()), '2025-02-15 16:45:00', '2025-02-15 16:45:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'RECHARGE',       NULL,                   100000,185000,   '충전',        5,  '2025-03', CONCAT('SEED:', UUID()), '2025-03-01 08:00:00', '2025-03-01 08:00:00'),
    (@MEMBER_ID, 'MONEY', 'WITHDRAWAL',  'BUY',            NULL,                   25000, 160000,   'LG 3GB 구매',  6,  '2025-03', CONCAT('SEED:', UUID()), '2025-03-10 11:15:00', '2025-03-10 11:15:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'TRADE_CANCEL',   NULL,                   25000, 185000,   '거래 취소 환불',7, '2025-04', CONCAT('SEED:', UUID()), '2025-04-02 13:30:00', '2025-04-02 13:30:00'),
    (@MEMBER_ID, 'MONEY', 'WITHDRAWAL',  'RECHARGE_CANCEL', NULL,                  50000, 135000,   '충전 취소',    8,  '2025-04', CONCAT('SEED:', UUID()), '2025-04-20 17:00:00', '2025-04-20 17:00:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'RECHARGE',       NULL,                   200000,335000,   '충전',        9,  '2025-05', CONCAT('SEED:', UUID()), '2025-05-05 09:00:00', '2025-05-05 09:00:00'),
    (@MEMBER_ID, 'MONEY', 'WITHDRAWAL',  'BUY',            NULL,                   10000, 325000,   'SKT 1GB 구매', 10, '2025-05', CONCAT('SEED:', UUID()), '2025-05-18 15:20:00', '2025-05-18 15:20:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'SELL',           NULL,                   35000, 360000,   'KT 20GB 판매', 11, '2025-06', CONCAT('SEED:', UUID()), '2025-06-01 10:00:00', '2025-06-01 10:00:00'),
    (@MEMBER_ID, 'MONEY', 'WITHDRAWAL',  'SETTLEMENT',     NULL,                   50000, 310000,   '정산',        12, '2025-06', CONCAT('SEED:', UUID()), '2025-06-15 12:00:00', '2025-06-15 12:00:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'RECHARGE',       NULL,                   80000, 390000,   '충전',        13, '2025-07', CONCAT('SEED:', UUID()), '2025-07-03 08:30:00', '2025-07-03 08:30:00'),
    (@MEMBER_ID, 'MONEY', 'WITHDRAWAL',  'BUY',            NULL,                   45000, 345000,   'LG 15GB 구매', 14, '2025-07', CONCAT('SEED:', UUID()), '2025-07-22 14:10:00', '2025-07-22 14:10:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'RECHARGE',       NULL,                   60000, 405000,   '충전',        15, '2025-08', CONCAT('SEED:', UUID()), '2025-08-10 09:45:00', '2025-08-10 09:45:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'SELL',           NULL,                   18000, 423000,   'SKT 7GB 판매', 16, '2025-09', CONCAT('SEED:', UUID()), '2025-09-05 11:00:00', '2025-09-05 11:00:00'),
    (@MEMBER_ID, 'MONEY', 'WITHDRAWAL',  'BUY',            NULL,                   30000, 393000,   'KT 5GB 구매',  17, '2025-10', CONCAT('SEED:', UUID()), '2025-10-12 16:30:00', '2025-10-12 16:30:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'RECHARGE',       NULL,                   150000,543000,   '충전',        18, '2025-11', CONCAT('SEED:', UUID()), '2025-11-01 08:00:00', '2025-11-01 08:00:00'),
    (@MEMBER_ID, 'MONEY', 'WITHDRAWAL',  'BUY',            NULL,                   22000, 521000,   'LG 8GB 구매',  19, '2025-12', CONCAT('SEED:', UUID()), '2025-12-20 13:40:00', '2025-12-20 13:40:00'),
    (@MEMBER_ID, 'MONEY', 'DEPOSIT',     'RECHARGE',       NULL,                   70000, 591000,   '충전',        20, '2026-01', CONCAT('SEED:', UUID()), '2026-01-15 10:20:00', '2026-01-15 10:20:00');

-- 더블링: 20 → 40 → 80 → ... → 1,310,720 (약 100만)
-- 각 라운드마다 UUID()로 고유한 idempotency_key 생성
-- created_at에 INTERVAL로 시간 분산 (동일 시간대 몰림 방지)

SET @round = 0;

-- Round 1: 20 → 40
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:1:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 2: 40 → 80
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:2:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 3: 80 → 160
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:3:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 4: 160 → 320
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:4:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 5: 320 → 640
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:5:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 6: 640 → 1,280
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:6:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 7: 1,280 → 2,560
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:7:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 8: 2,560 → 5,120
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:8:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 9: 5,120 → 10,240
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:9:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 10: 10,240 → 20,480
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:10:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 11: 20,480 → 40,960
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:11:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 12: 40,960 → 81,920
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:12:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 13: 81,920 → 163,840
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:13:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 14: 163,840 → 327,680
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:14:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 15: 327,680 → 655,360
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:15:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- Round 16: 655,360 → 1,310,720 (약 131만)
INSERT INTO asset_history (member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month, idempotency_key, created_at, updated_at)
SELECT member_id, asset_type, transaction_type, category, transaction_detail, amount, balance_after, title, source_id, tx_year_month,
       CONCAT('GEN:16:', UUID()),
       DATE_ADD(created_at, INTERVAL FLOOR(RAND() * 525600) MINUTE),
       NOW()
FROM asset_history WHERE member_id = @MEMBER_ID;

-- ─── 4. tx_year_month 보정 (created_at 기준으로 재계산) ───
UPDATE asset_history
SET tx_year_month = DATE_FORMAT(created_at, '%Y-%m')
WHERE member_id = @MEMBER_ID;

-- ─── 5. 건수 확인 ───
SELECT COUNT(*) AS total_asset_history FROM asset_history WHERE member_id = @MEMBER_ID;
SELECT tx_year_month, COUNT(*) AS cnt FROM asset_history WHERE member_id = @MEMBER_ID GROUP BY tx_year_month ORDER BY tx_year_month;
