#!/usr/bin/env bash
# =============================================================================
# 에스크로 흐름 E2E 테스트 스크립트
# =============================================================================
# 전제조건:
#   1. 서버가 loadtest 프로필로 실행 중
#      ./gradlew bootRun --args='--spring.profiles.active=loadtest'
#   2. jq 설치: brew install jq
#
# 테스트 흐름 2가지:
#   A. SELL 카드 흐름: 판매자가 SELL 카드 등록 → 구매자가 즉시 구매
#      (판매자 자동 배정, acceptBuyRequest 불필요)
#   B. BUY 카드 흐름:  구매자가 BUY 카드 등록 → 구매자가 구매 신청 → 판매자가 수락
#
#   이후 공통: mark-data-sent (loadtest 전용, S3·SMS 스킵) → 구매자 확인
# =============================================================================

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
CARD_PRICE=5000   # 카드 단가 (=구매 금액)

# ── 색상 출력 ────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log_step()  { echo -e "\n${BLUE}══ STEP $1 ══${NC}"; }
log_ok()    { echo -e " ${GREEN}✓${NC} $1"; }
log_info()  { echo -e " ${YELLOW}ℹ${NC} $1"; }
log_error() { echo -e " ${RED}✗ ERROR:${NC} $1"; exit 1; }
log_res()   { echo "   응답: $(echo "$1" | head -c 200)"; }

# ── JWT 추출 (Authorization 응답 헤더) ───────────────────────────────────────
extract_token() {
    grep -i "^authorization:" | sed 's/[Aa]uthorization:[ ]*[Bb]earer //i' | tr -d '\r\n'
}

# ── API 응답 성공 검증 (2xx 코드 포함 여부) ──────────────────────────────────
assert_ok() {
    local resp=$1 label=$2
    local http_status
    http_status=$(echo "$resp" | jq -r '.status // empty' 2>/dev/null)
    if echo "$resp" | jq -e '.code' 2>/dev/null | grep -q "ERROR\|FAIL\|MISMATCH\|NOT_FOUND\|DENIED\|DUPLICATE"; then
        log_error "$label 실패: $resp"
    fi
    # HTTP status 필드가 있으면 OK/CREATED/... 중 하나여야 함
    if [[ -n "$http_status" ]] && [[ "$http_status" != "OK" && "$http_status" != "CREATED" ]]; then
        log_error "$label 실패 (status=$http_status): $resp"
    fi
}

# ── jq 확인 ──────────────────────────────────────────────────────────────────
command -v jq >/dev/null 2>&1 || log_error "jq가 필요합니다. brew install jq"

# ── 계정 정보 ────────────────────────────────────────────────────────────────
BUYER_EMAIL="e2e-buyer@test.com";  BUYER_PW="Test1234!";  BUYER_NICK="buyer01";  BUYER_PHONE="01011111111"
SELLER_EMAIL="e2e-seller@test.com"; SELLER_PW="Test1234!"; SELLER_NICK="seller01"; SELLER_PHONE="01022222222"

echo ""
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${YELLOW}  에스크로 E2E 테스트  |  서버: $BASE_URL${NC}"
echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
echo "흐름 선택:"
echo "  [A] SELL 카드 흐름 - 판매자 SELL 카드 등록 → 구매자 즉시 구매"
echo "  [B] BUY  카드 흐름 - 구매자 BUY 카드 등록 → 구매자 구매 신청 → 판매자 수락"
echo ""
read -p "선택 (A 또는 B): " FLOW
FLOW=$(echo "$FLOW" | tr '[:lower:]' '[:upper:]')
[[ "$FLOW" != "A" && "$FLOW" != "B" ]] && log_error "A 또는 B만 입력 가능"

# =============================================================================
# STEP 1: 계정 생성
# =============================================================================
log_step "1. 테스트 계정 생성 (loadtest 전용 엔드포인트)"

create_account() {
    local email=$1 pw=$2 nick=$3 phone=$4
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST "$BASE_URL/api/loadtest/join" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$email\",\"password\":\"$pw\",\"name\":\"$nick\",\"nickname\":\"$nick\",\"phone\":\"$phone\"}")
    echo "$http_code"
}

code=$(create_account "$BUYER_EMAIL" "$BUYER_PW" "$BUYER_NICK" "$BUYER_PHONE")
[[ "$code" == "200" ]] && log_ok "구매자 생성: $BUYER_EMAIL" || log_info "구매자 이미 존재 (HTTP $code)"

code=$(create_account "$SELLER_EMAIL" "$SELLER_PW" "$SELLER_NICK" "$SELLER_PHONE")
[[ "$code" == "200" ]] && log_ok "판매자 생성: $SELLER_EMAIL" || log_info "판매자 이미 존재 (HTTP $code)"

log_info "이벤트 체인 대기 (Wallet 생성 → 가입보너스, RabbitMQ 비동기)..."
sleep 3

# =============================================================================
# STEP 2: 로그인 → JWT 토큰 획득
# =============================================================================
log_step "2. 로그인"

login() {
    local email=$1 pw=$2
    curl -s -D - -o /dev/null \
        -X POST "$BASE_URL/api/login" \
        -H "Content-Type: application/json" \
        -d "{\"email\":\"$email\",\"password\":\"$pw\"}" | extract_token
}

BUYER_TOKEN=$(login "$BUYER_EMAIL" "$BUYER_PW")
[[ -z "$BUYER_TOKEN" ]] && log_error "구매자 로그인 실패"
log_ok "구매자 JWT: ${BUYER_TOKEN:0:40}..."

SELLER_TOKEN=$(login "$SELLER_EMAIL" "$SELLER_PW")
[[ -z "$SELLER_TOKEN" ]] && log_error "판매자 로그인 실패"
log_ok "판매자 JWT: ${SELLER_TOKEN:0:40}..."

# =============================================================================
# STEP 3: 구매자 머니 충전 (MockPaymentGatewayAdapter - 항상 성공)
# =============================================================================
log_step "3. 구매자 머니 충전 (Mock Toss, ${CARD_PRICE}원)"

prepare_resp=$(curl -s -X POST "$BASE_URL/api/money/recharge/prepare" \
    -H "Authorization: Bearer $BUYER_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"amount\": $CARD_PRICE}")
log_res "$prepare_resp"

ORDER_ID=$(echo "$prepare_resp" | jq -r '.data.orderId // empty')
[[ -z "$ORDER_ID" ]] && log_error "orderId 추출 실패"
log_ok "orderId: $ORDER_ID"

MOCK_KEY="mock-pk-$(date +%s)"
success_resp=$(curl -s -X GET \
    "$BASE_URL/api/money/recharge/success?paymentKey=$MOCK_KEY&orderId=$ORDER_ID&amount=$CARD_PRICE" \
    -H "Authorization: Bearer $BUYER_TOKEN")
log_res "$success_resp"
assert_ok "$success_resp" "머니 충전"
log_ok "구매자 ${CARD_PRICE}원 충전 완료"

# =============================================================================
# STEP 4 ~ 5: 카드 등록 + 트레이드 생성 (흐름별 분기)
# =============================================================================

if [[ "$FLOW" == "A" ]]; then
    # -------------------------------------------------------------------------
    # [A] SELL 카드 흐름
    #   판매자가 SELL 카드 등록 (SellStatus: SELLING)
    #   구매자가 POST /api/trades/sell 로 구매 → 판매자 자동 배정, PAYMENT_CONFIRMED
    # -------------------------------------------------------------------------
    log_step "4A. 판매자 SELL 카드 등록"

    card_resp=$(curl -s -X POST "$BASE_URL/api/cards" \
        -H "Authorization: Bearer $SELLER_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"cardCategory\":\"SELL\",\"carrier\":\"SKT\",\"dataAmount\":5,\"price\":$CARD_PRICE}")
    log_res "$card_resp"
    CARD_ID=$(echo "$card_resp" | jq -r '.data.cardId // empty')
    [[ -z "$CARD_ID" ]] && log_error "SELL 카드 등록 실패"
    log_ok "SELL 카드 등록. cardId: $CARD_ID"

    log_step "5A. 구매자 → SELL 카드 구매 (에스크로 차감, 판매자 자동 배정)"

    trade_resp=$(curl -s -X POST "$BASE_URL/api/trades/sell" \
        -H "Authorization: Bearer $BUYER_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"cardId\":$CARD_ID,\"money\":$CARD_PRICE,\"point\":0}")
    log_res "$trade_resp"
    TRADE_ID=$(echo "$trade_resp" | jq -r '.data.tradeId // empty')
    [[ -z "$TRADE_ID" ]] && log_error "트레이드 생성 실패"
    log_ok "트레이드 생성. tradeId: $TRADE_ID (PAYMENT_CONFIRMED, 에스크로 ${CARD_PRICE}원)"

else
    # -------------------------------------------------------------------------
    # [B] BUY 카드 흐름
    #   구매자가 BUY 카드 등록 (SellStatus: PENDING)
    #   구매자가 POST /api/trades/buy 로 구매 신청 → 카드 SELLING으로 전환, PAYMENT_CONFIRMED
    #   판매자가 POST /api/trades/buy/accept 로 수락 → 판매자 배정, TRADING
    # -------------------------------------------------------------------------
    log_step "4B. 구매자 BUY 카드 등록"

    card_resp=$(curl -s -X POST "$BASE_URL/api/cards" \
        -H "Authorization: Bearer $BUYER_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"cardCategory\":\"BUY\",\"carrier\":\"KT\",\"dataAmount\":3,\"price\":$CARD_PRICE}")
    log_res "$card_resp"
    CARD_ID=$(echo "$card_resp" | jq -r '.data.cardId // empty')
    [[ -z "$CARD_ID" ]] && log_error "BUY 카드 등록 실패"
    log_ok "BUY 카드 등록. cardId: $CARD_ID"

    log_step "5B-1. 구매자 → 본인 BUY 카드로 구매 신청 (에스크로 차감)"

    trade_resp=$(curl -s -X POST "$BASE_URL/api/trades/buy" \
        -H "Authorization: Bearer $BUYER_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"cardId\":$CARD_ID,\"money\":$CARD_PRICE,\"point\":0}")
    log_res "$trade_resp"
    TRADE_ID=$(echo "$trade_resp" | jq -r '.data.tradeId // empty')
    [[ -z "$TRADE_ID" ]] && log_error "트레이드 생성 실패"
    log_ok "트레이드 생성. tradeId: $TRADE_ID (카드 SELLING 전환됨, 에스크로 ${CARD_PRICE}원)"

    log_step "5B-2. 판매자 → BUY 카드 수락 (판매자 배정)"

    accept_resp=$(curl -s -X POST "$BASE_URL/api/trades/buy/accept" \
        -H "Authorization: Bearer $SELLER_TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"cardId\":$CARD_ID}")
    log_res "$accept_resp"
    log_ok "판매자 수락 완료 (카드 TRADING, 판매자 배정됨)"
fi

# =============================================================================
# STEP 6: 판매자 데이터 전송 (loadtest 전용 엔드포인트 - S3·SMS 스킵)
# =============================================================================
log_step "6. 판매자 데이터 전송 (mark-data-sent, loadtest 전용)"

sent_resp=$(curl -s -X POST "$BASE_URL/api/loadtest/trades/$TRADE_ID/mark-data-sent")
log_res "$sent_resp"
assert_ok "$sent_resp" "mark-data-sent"
log_ok "DATA_SENT 상태 전환 완료"

# =============================================================================
# STEP 7: 구매자 거래 확인 (에스크로 → 판매자 정산)
# =============================================================================
log_step "7. 구매자 거래 확인 (에스크로 해제 → 판매자 정산)"

confirm_resp=$(curl -s -X PATCH "$BASE_URL/api/trades/$TRADE_ID/confirm" \
    -H "Authorization: Bearer $BUYER_TOKEN")
log_res "$confirm_resp"
assert_ok "$confirm_resp" "거래 확인"
log_ok "거래 확인 완료 → 에스크로 ${CARD_PRICE}원 판매자에게 정산"

# =============================================================================
# STEP 8: 최종 트레이드 상태 확인
# =============================================================================
log_step "8. 최종 트레이드 상태"

trade_detail=$(curl -s -X GET "$BASE_URL/api/trades/$TRADE_ID" \
    -H "Authorization: Bearer $BUYER_TOKEN")

FINAL_STATUS=$(echo "$trade_detail" | jq -r '.data.status // empty')
echo ""
echo "$trade_detail" | jq '{tradeId: .data.tradeId, status: .data.status}' 2>/dev/null \
    || echo "   $trade_detail" | head -c 300

# 트레이드 상태 검증
[[ "$FINAL_STATUS" == "COMPLETED" ]] \
    && log_ok "트레이드 상태: COMPLETED ✓" \
    || log_error "트레이드 상태 기대값 COMPLETED, 실제값: $FINAL_STATUS"

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}  E2E 에스크로 테스트 완료  |  흐름: $FLOW  |  tradeId: $TRADE_ID${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
