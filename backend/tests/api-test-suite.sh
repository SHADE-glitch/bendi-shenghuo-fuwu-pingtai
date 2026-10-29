#!/bin/bash

# Local-life-service-platform API Test Suite
# Base URL
BASE_URL="http://localhost:8081"
TOKEN=""

# Redis 已启用 requirepass（见 docker-compose.yml）：带上密码，否则 redis-cli 会被 NOAUTH 拒绝。
# 值取 .env 的 REDIS_PASSWORD；不设则按无认证处理（兼容未启用密码的环境）。
REDIS_PASSWORD="${REDIS_PASSWORD:-}"
redis_cli() {
    if [ -n "$REDIS_PASSWORD" ]; then
        redis-cli -a "$REDIS_PASSWORD" --no-auth-warning "$@"
    else
        redis-cli "$@"
    fi
}

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test counters
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_SKIPPED=0

# Logging
log() {
    echo -e "$1"
}

pass() {
    log "${GREEN}[PASS]${NC} $1"
    ((TESTS_PASSED++))
}

fail() {
    log "${RED}[FAIL]${NC} $1"
    ((TESTS_FAILED++))
}

skip() {
    log "${YELLOW}[SKIP]${NC} $1"
    ((TESTS_SKIPPED++))
}

# ============================================================================
# PHASE 2: Authentication & User Center Testing
# ============================================================================
log "\n=========================================="
log "PHASE 2: Authentication & User Center Testing"
log "=========================================="

# Test 1: Login with existing user (use mock code)
log "\n[Test 1] Mock verification code for phone 13686869696..."
curl -s -X POST "$BASE_URL/users/code/mock?phone=13686869696&code=123456" > /dev/null

log "[Test 1b] Login with phone number and mock code..."
RESPONSE=$(curl -s -X POST "$BASE_URL/users/login" \
    -H "Content-Type: application/json" \
    -d '{"phone": "13686869696", "code": "123456"}')

if echo "$RESPONSE" | grep -q "token"; then
    TOKEN=$(echo "$RESPONSE" | grep -o '"token":"[^"]*"' | sed 's/"token":"\([^"]*\)"/\1/')
    pass "Login successful, token obtained (length: ${#TOKEN})"
else
    fail "Login failed: $RESPONSE"
fi

# Test 2: Get current user info
log "\n[Test 2] Get current user info..."
if [ -n "$TOKEN" ]; then
    RESPONSE=$(curl -s -X GET "$BASE_URL/users/me" \
        -H "authorization: $TOKEN")
    
    if echo "$RESPONSE" | grep -q "nick_name\|phone"; then
        pass "Got current user info: $RESPONSE"
    else
        fail "Failed to get user info: $RESPONSE"
    fi
else
    skip "Skipping - no token from login"
fi

# Test 3: Update user info
log "\n[Test 3] Update user info..."
if [ -n "$TOKEN" ]; then
    RESPONSE=$(curl -s -X PUT "$BASE_URL/users/info" \
        -H "authorization: $TOKEN" \
        -H "Content-Type: application/json" \
        -d '{
            "introduce": "API 测试用户 - 2026 年 9 月",
            "city": "hangzhou",
            "gender": 1,
            "birthday": "1990-01-01"
        }')
    
    if echo "$RESPONSE" | grep -q "success\|code.*0"; then
        pass "User info updated successfully"
    else
        fail "Failed to update user info: $RESPONSE"
    fi
else
    skip "Skipping - no token from login"
fi

# Test 4: Verify updated user info
log "\n[Test 4] Verify updated user info..."
if [ -n "$TOKEN" ]; then
    RESPONSE=$(curl -s -X GET "$BASE_URL/users/me" \
        -H "authorization: $TOKEN")
    
    if echo "$RESPONSE" | grep -q "hangzhou"; then
        pass "Updated info verified: city=hangzhou"
    else
        fail "Update not reflected in user info: $RESPONSE"
    fi
else
    skip "Skipping - no token from login"
fi

# Test 5: Access without token (expect 401)
log "\n[Test 5] Access protected endpoint without token..."
RESPONSE=$(curl -s -w "\n%{http_code}" -X GET "$BASE_URL/users/me" \
    -o /dev/null)

if [ "$RESPONSE" = "401" ]; then
    pass "Correctly rejected unauthenticated request (401)"
else
    fail "Should have returned 401, got: $RESPONSE"
fi

# Test 6: Access with invalid token
log "\n[Test 6] Access with invalid token..."
RESPONSE=$(curl -s -X GET "$BASE_URL/users/me" \
    -H "authorization: invalid_token_12345")

if echo "$RESPONSE" | grep -q "unauthorized\|AUTH_REQUIRED"; then
    pass "Invalid token correctly rejected"
else
    fail "Invalid token should be rejected: $RESPONSE"
fi

# Test 7: Sign-in functionality
log "\n[Test 7] User sign-in..."
if [ -n "$TOKEN" ]; then
    RESPONSE=$(curl -s -X POST "$BASE_URL/users/signs" \
        -H "authorization: $TOKEN")
    
    if echo "$RESPONSE" | grep -q "success\|code.*0"; then
        pass "Sign-in successful"
    else
        fail "Sign-in failed: $RESPONSE"
    fi
else
    skip "Skipping - no token from login"
fi

# Test 8: Check sign-in count
log "\n[Test 8] Get continuous sign-in count..."
if [ -n "$TOKEN" ]; then
    RESPONSE=$(curl -s -X GET "$BASE_URL/users/signs/count" \
        -H "authorization: $TOKEN")
    
    if echo "$RESPONSE" | grep -qE "[0-9]+"; then
        pass "Sign-in count retrieved: $RESPONSE"
    else
        fail "Failed to get sign-in count: $RESPONSE"
    fi
else
    skip "Skipping - no token from login"
fi

# ============================================================================
# PHASE 3: Shop Functionality Testing
# ============================================================================
log "\n\n=========================================="
log "PHASE 3: Shop Functionality Testing"
log "=========================================="

# Test 9: Get shop list by type
log "\n[Test 9] Get shop list by type (typeId=1)..."
RESPONSE=$(curl -s "$BASE_URL/shops?typeId=1&current=1&page=10")

if echo "$RESPONSE" | grep -q "name\|address"; then
    SHOP_COUNT=$(echo "$RESPONSE" | grep -o '"id"' | wc -l)
    pass "Got $SHOP_COUNT shops of type 1"
else
    fail "Failed to get shop list: $RESPONSE"
fi

# Test 10: Get shop detail
log "\n[Test 10] Get shop detail (shopId=1)..."
RESPONSE=$(curl -s "$BASE_URL/shops/1")

if echo "$RESPONSE" | grep -q "name\|address\|type_id"; then
    pass "Got shop detail: $(echo "$RESPONSE" | grep -o '"name":"[^"]*"' | head -1)"
else
    fail "Failed to get shop detail: $RESPONSE"
fi

# Test 11: Non-existent shop ID
log "\n[Test 11] Query non-existent shop (id=999999)..."
RESPONSE=$(curl -s "$BASE_URL/shops/999999")

if echo "$RESPONSE" | grep -q "NOT_FOUND\|null\|404"; then
    pass "Correctly handled non-existent shop"
else
    fail "Should return null/not found: $RESPONSE"
fi

# Test 12: Cache performance test
log "\n[Test 12] Cache performance test..."
T1_START=$(date +%s%N)
curl -s "$BASE_URL/shops/1" > /dev/null
T1_END=$(date +%s%N)
T1=$((($T1_END - $T1_START) / 1000000))

T2_START=$(date +%s%N)
curl -s "$BASE_URL/shops/1" > /dev/null
T2_END=$(date +%s%N)
T2=$((($T2_END - $T2_START) / 1000000))

log "  First request: ${T1}ms, Second request: ${T2}ms"
if [ $T2 -lt $T1 ]; then
    pass "Cache improved response time (${T1}ms -> ${T2}ms)"
else
    skip "No cache improvement detected (may be expected on first access)"
fi

# Test 13: Search shops by name
log "\n[Test 13] Search shops by name..."
RESPONSE=$(curl -s "$BASE_URL/shops/search?name=餐厅&current=1")

if echo "$RESPONSE" | grep -q "name\|data"; then
    SEARCH_COUNT=$(echo "$RESPONSE" | grep -o '"id"' | wc -l)
    pass "Found $SEARCH_COUNT shops matching '餐厅'"
else
    fail "Search failed: $RESPONSE"
fi

# Test 14: GEO nearby shops query
log "\n[Test 14] GEO nearby shops query..."
RESPONSE=$(curl -s "$BASE_URL/shops?typeId=1&x=120.151954&y=30.32497&current=1")

if echo "$RESPONSE" | grep -q "distance\|x\|y"; then
    GEO_COUNT=$(echo "$RESPONSE" | grep -o '"id"' | wc -l)
    pass "Got $GEO_COUNT shops near coordinates"
else
    fail "GEO query failed: $RESPONSE"
fi

# Test 15: Invalid GEO coordinates
log "\n[Test 15] Invalid GEO coordinates..."
RESPONSE=$(curl -s "$BASE_URL/shops?typeId=1&x=999&y=999&current=1")

if echo "$RESPONSE" | grep -q "data\|\[\]"; then
    pass "Handled invalid coordinates gracefully"
else
    fail "Unexpected response: $RESPONSE"
fi

# Test 16: Get shop types
log "\n[Test 16] Get shop types list..."
RESPONSE=$(curl -s "$BASE_URL/shop-types")

if echo "$RESPONSE" | grep -q "name\|icon\|sort"; then
    TYPE_COUNT=$(echo "$RESPONSE" | grep -o '"id"' | wc -l)
    pass "Got $TYPE_COUNT shop types"
else
    fail "Failed to get shop types: $RESPONSE"
fi

# ============================================================================
# PHASE 4: Seckill Voucher Testing  
# ============================================================================
log "\n\n=========================================="
log "PHASE 4: Seckill Voucher Testing"
log "=========================================="

# Use voucher ID 1025 (active, stock=225)
SECKILL_VOUCHER_ID=1025

# Test 17: Query vouchers by shop
log "\n[Test 17] Query vouchers by shop..."
RESPONSE=$(curl -s "$BASE_URL/vouchers?shopId=1")

if echo "$RESPONSE" | grep -q "title\|pay_value\|actual_value"; then
    VOUCHER_COUNT=$(echo "$RESPONSE" | grep -o '"id"' | wc -l)
    pass "Got $VOUCHER_COUNT vouchers for shop 1"
else
    fail "Failed to query vouchers: $RESPONSE"
fi

# Test 18: Initial stock check
log "\n[Test 18] Check initial stock for voucher $SECKILL_VOUCHER_ID..."
STOCK_BEFORE=$(redis_cli GET "seckill:stock:$SECKILL_VOUCHER_ID")
if [ -n "$STOCK_BEFORE" ]; then
    pass "Initial Redis stock: $STOCK_BEFORE"
else
    skip "Redis stock key not found (will be created on first seckill)"
fi

# Test 19: Seckill voucher
log "\n[Test 19] Seckill voucher (id=$SECKILL_VOUCHER_ID)..."
if [ -n "$TOKEN" ]; then
    RESPONSE=$(curl -s -X POST "$BASE_URL/voucher-orders/seckill-orders?voucherId=$SECKILL_VOUCHER_ID" \
        -H "authorization: $TOKEN")
    
    ORDER_ID=$(echo "$RESPONSE" | grep -o '"orderId":[0-9]*' | cut -d':' -f2)
    
    if [ -n "$ORDER_ID" ]; then
        pass "Seckill successful, orderId=$ORDER_ID"
        
        # Save order ID for later tests
        SECKILL_ORDER_ID=$ORDER_ID
        
        # Test 20: Verify order creation
        log "\n[Test 20] Verify order detail..."
        ORDER_RESPONSE=$(curl -s "$BASE_URL/voucher-orders/$ORDER_ID" \
            -H "authorization: $TOKEN")
        
        if echo "$ORDER_RESPONSE" | grep -q "voucher_id\|user_id\|status"; then
            pass "Order detail verified: status=$(echo "$ORDER_RESPONSE" | grep -o '"status":[0-9]*' | cut -d':' -f2)"
        else
            fail "Order detail mismatch: $ORDER_RESPONSE"
        fi
        
        # Test 21: Verify stock deduction
        log "\n[Test 21] Verify stock deduction..."
        STOCK_AFTER=$(redis_cli GET "seckill:stock:$SECKILL_VOUCHER_ID")
        if [ -n "$STOCK_AFTER" ] && [ "$STOCK_AFTER" -eq $((STOCK_BEFORE - 1)) ]; then
            pass "Stock correctly deducted: $STOCK_BEFORE -> $STOCK_AFTER"
        elif [ "$STOCK_BEFORE" = "null" ]; then
            pass "Stock created and deducted: null -> $STOCK_AFTER"
        else
            fail "Stock mismatch: before=$STOCK_BEFORE, after=$STOCK_AFTER"
        fi
    else
        fail "Seckill failed: $RESPONSE"
    fi
else
    skip "Skipping - no token from login"
fi

# Test 22: One person one single restriction
log "\n[Test 22] Test one person one single restriction..."
if [ -n "$TOKEN" ]; then
    RESPONSE=$(curl -s -X POST "$BASE_URL/voucher-orders/seckill-orders?voucherId=$SECKILL_VOUCHER_ID" \
        -H "authorization: $TOKEN")
    
    if echo "$RESPONSE" | grep -q "DUPLICATE\|一人一单\|ORDER_DUPLICATE"; then
        pass "Duplicate order correctly blocked"
    else
        fail "Should block duplicate order: $RESPONSE"
    fi
else
    skip "Skipping - no token from login"
fi

# Test 23: Invalid voucher ID
log "\n[Test 23] Seckill with invalid voucher ID..."
if [ -n "$TOKEN" ]; then
    RESPONSE=$(curl -s -X POST "$BASE_URL/voucher-orders/seckill-orders?voucherId=99999" \
        -H "authorization: $TOKEN")
    
    if echo "$RESPONSE" | grep -q "VOUCHER_NOT_FOUND\|not found"; then
        pass "Invalid voucher correctly rejected"
    else
        fail "Should reject invalid voucher: $RESPONSE"
    fi
else
    skip "Skipping - no token from login"
fi

# Test 24: Missing voucherId parameter
log "\n[Test 24] Seckill without voucherId parameter..."
if [ -n "$TOKEN" ]; then
    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/voucher-orders/seckill-orders" \
        -H "authorization: $TOKEN" \
        -o /dev/null)
    
    if [ "$RESPONSE" = "400" ] || [ "$RESPONSE" = "404" ]; then
        pass "Missing parameter correctly rejected ($RESPONSE)"
    else
        fail "Should reject missing parameter: $RESPONSE"
    fi
else
    skip "Skipping - no token from login"
fi

# ============================================================================
# PHASE 5: Order Lifecycle Testing
# ============================================================================
log "\n\n=========================================="
log "PHASE 5: Order Lifecycle Testing"
log "=========================================="

# Use the order ID from Test 19
if [ -n "$SECKILL_ORDER_ID" ]; then
    
    # Test 25: Get my vouchers list
    log "\n[Test 25] Get my vouchers list (tab=unused)..."
    RESPONSE=$(curl -s "$BASE_URL/voucher-orders/mine?tab=unused&current=1&size=10" \
        -H "authorization: $TOKEN")
    
    if echo "$RESPONSE" | grep -q "orderId\|voucher_id"; then
        MY_VOUCHER_COUNT=$(echo "$RESPONSE" | grep -o '"orderId"' | wc -l)
        pass "Got $MY_VOUCHER_COUNT unused vouchers"
    else
        fail "Failed to get my vouchers: $RESPONSE"
    fi
    
    # Test 26: Pay the order
    log "\n[Test 26] Pay order (orderId=$SECKILL_ORDER_ID)..."
    RESPONSE=$(curl -s -X POST "$BASE_URL/voucher-orders/$SECKILL_ORDER_ID/pay?payType=1" \
        -H "authorization: $TOKEN")
    
    if echo "$RESPONSE" | grep -q "success\|code.*0"; then
        pass "Order paid successfully"
        
        # Test 27: Verify payment status
        log "\n[Test 27] Verify payment status..."
        STATUS_RESPONSE=$(curl -s "$BASE_URL/voucher-orders/$SECKILL_ORDER_ID/status" \
            -H "authorization: $TOKEN")
        
        PAYMENT_STATUS=$(echo "$STATUS_RESPONSE" | grep -o '"code":[0-9]*' | cut -d':' -f2)
        if [ "$PAYMENT_STATUS" = "2" ]; then
            pass "Status correctly updated to PAID (2)"
        else
            fail "Status should be 2 (PAID), got: $STATUS_RESPONSE"
        fi
    else
        fail "Payment failed: $RESPONSE"
    fi
    
    # Test 28: Duplicate payment attempt
    log "\n[Test 28] Attempt duplicate payment..."
    RESPONSE=$(curl -s -X POST "$BASE_URL/voucher-orders/$SECKILL_ORDER_ID/pay?payType=1" \
        -H "authorization: $TOKEN")
    
    if echo "$RESPONSE" | grep -q "ORDER_STATUS_INVALID\|invalid"; then
        pass "Duplicate payment correctly rejected"
    else
        fail "Should reject duplicate payment: $RESPONSE"
    fi
    
    # Test 29: Cancel pending order (need to refund first or create new order)
    log "\n[Test 29] Create new order for cancellation test..."
    RESPONSE=$(curl -s -X POST "$BASE_URL/voucher-orders/seckill-orders?voucherId=$SECKILL_VOUCHER_ID" \
        -H "authorization: $TOKEN")
    
    NEW_ORDER_ID=$(echo "$RESPONSE" | grep -o '"orderId":[0-9]*' | cut -d':' -f2)
    
    if [ -n "$NEW_ORDER_ID" ]; then
        pass "Created new order: $NEW_ORDER_ID"
        
        # Pay it first
        curl -s -X POST "$BASE_URL/voucher-orders/$NEW_ORDER_ID/pay?payType=1" \
            -H "authorization: $TOKEN" > /dev/null
        
        # Now cancel it (refund then cancel)
        log "\n[Test 30] Refund paid order..."
        RESPONSE=$(curl -s -X POST "$BASE_URL/voucher-orders/$NEW_ORDER_ID/refund" \
            -H "authorization: $TOKEN")
        
        if echo "$RESPONSE" | grep -q "success\|code.*0"; then
            pass "Refund successful"
            
            # Delete the refunded order
            log "\n[Test 31] Delete refunded order..."
            RESPONSE=$(curl -s -X DELETE "$BASE_URL/voucher-orders/$NEW_ORDER_ID" \
                -H "authorization: $TOKEN")
            
            if echo "$RESPONSE" | grep -q "success\|code.*0"; then
                pass "Order deleted successfully"
            else
                fail "Delete failed: $RESPONSE"
            fi
        else
            fail "Refund failed: $RESPONSE"
        fi
    else
        skip "Could not create test order: $RESPONSE"
    fi
    
    # Test 32: Access other user's order (create second user, try to access first user's order)
    log "\n[Test 32] Cross-user access prevention..."
    
    # Login as different user
    RESPONSE=$(curl -s -X POST "$BASE_URL/users/login" \
        -H "Content-Type: application/json" \
        -d '{"phone": "13838411438"}')
    
    OTHER_USER_TOKEN=$(echo "$RESPONSE" | grep -o '"token":"[^"]*"' | sed 's/"token":"\([^"]*\)"/\1/')
    
    if [ -n "$OTHER_USER_TOKEN" ]; then
        # Try to access original user's order
        RESPONSE=$(curl -s "$BASE_URL/voucher-orders/$SECKILL_ORDER_ID" \
            -H "authorization: $OTHER_USER_TOKEN")
        
        if echo "$RESPONSE" | grep -q "无权\|permission\|FORBIDDEN"; then
            pass "Cross-user access correctly prevented"
        else
            fail "Should prevent cross-user access: $RESPONSE"
        fi
    else
        skip "Could not create second user for testing"
    fi
    
    # Test 33: Non-existent order
    log "\n[Test 33] Query non-existent order..."
    RESPONSE=$(curl -s "$BASE_URL/voucher-orders/999999999" \
        -H "authorization: $TOKEN")
    
    if echo "$RESPONSE" | grep -q "订单不存在\|not found\|ERROR"; then
        pass "Non-existent order correctly handled"
    else
        fail "Should handle non-existent order: $RESPONSE"
    fi
    
else
    skip "Skipping - no seckill order created in Phase 4"
fi

# ============================================================================
# PHASE 6: Performance Testing (Read-only endpoints only)
# ============================================================================
log "\n\n=========================================="
log "PHASE 6: Performance Testing"
log "=========================================="

# Test 34: Baseline - Single request timing
log "\n[Test 34] Baseline timing (10 iterations)..."
TOTAL_TIME=0
for i in {1..10}; do
    START=$(date +%s%N)
    curl -s "$BASE_URL/shops/1" > /dev/null
    END=$(date +%s%N)
    ELAPSED=$((($END - $START) / 1000000))
    TOTAL_TIME=$((TOTAL_TIME + ELAPSED))
done
AVG_TIME=$((TOTAL_TIME / 10))
pass "Average response time: ${AVG_TIME}ms"

# Test 35: Apache Bench (if available) - Shop list endpoint
if command -v ab &> /dev/null; then
    log "\n[Test 35] Apache Bench load test (100 requests, 10 concurrency)..."
    AB_OUTPUT=$(ab -n 100 -c 10 \
        -H "authorization: $TOKEN" \
        "$BASE_URL/shops?typeId=1&current=1" 2>&1)
    
    QPS=$(echo "$AB_OUTPUT" | grep -o "Requests per second: [0-9.]*" | grep -o "[0-9.]*")
    AVG_RESP=$(echo "$AB_OUTPUT" | grep -o "Time per request: [0-9.]*" | grep -o "[0-9.]*" | head -1)
    
    if [ -n "$QPS" ]; then
        pass "QPS: $QPS, Avg response: ${AVG_RESP}ms"
    else
        skip "Could not parse ab results"
    fi
else
    skip "Apache Bench (ab) not installed, skipping load test"
fi

# ============================================================================
# Summary
# ============================================================================
log "\n\n=========================================="
log "TEST SUMMARY"
log "=========================================="
log "Passed:  ${GREEN}$TESTS_PASSED${NC}"
log "Failed:  ${RED}$TESTS_FAILED${NC}"
log "Skipped: ${YELLOW}$TESTS_SKIPPED${NC}"
log "Total:   $((TESTS_PASSED + TESTS_FAILED + TESTS_SKIPPED))"

if [ $TESTS_FAILED -eq 0 ]; then
    log "\n${GREEN}All critical tests passed!${NC}"
    exit 0
else
    log "\n${RED}Some tests failed. Please review the output above.${NC}"
    exit 1
fi
