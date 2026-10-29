#!/usr/bin/env bash
# ============================================================================
# api-crud-smoke.sh —— 可重复执行的接口回归脚本（普通 CRUD + 安全边界）
#
# 覆盖范围：
#   1. 认证边界：匿名可读 / 写接口必须登录（401）
#   2. 笔记内容管理：新增 → 编辑 → 越权编辑/删除 → 本人删除
#   3. 商铺评价：发布 → 编辑 → 越权编辑 → 评分越界 → 删除
#   4. 意见反馈：提交 → 列表 → 越权删除 → 本人删除
#   5. 消息：全部已读
#   6. 浏览历史：店铺/笔记去重 → 非法类型 → 目标不存在 → 列表 → 清空
#   7. 社交闭环：关注 → 他人主页 followedByMe=true → 取关
#   8. 订单状态归属：越权按「订单不存在」应答（-1）
#   9. 分页上限：size 超限被截断
#
# 特点：自带数据准备与清理，可重复执行；结果同时打印并落盘到
#       backend/test-results/api/api-crud-smoke-<timestamp>.log
#
# 用法：bash backend/tests/api-crud-smoke.sh
# 依赖：curl、jq；本地 Docker（容器名可通过环境变量覆盖）
# ============================================================================
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8081}"
REDIS_CONTAINER="${REDIS_CONTAINER:-Local-life-service-platform-redis}"
# Redis 已启用 requirepass（见 docker-compose.yml）；不传则 redis-cli 会被 NOAUTH 拒绝，
# 而下面的 redis_exec 原本把 stderr 一起丢弃，会让「认证失败」伪装成「登录失败」。
REDIS_PASSWORD="${REDIS_PASSWORD:-}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-Local-life-service-platform-mysql}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-rootpassword}"
MYSQL_DB="${MYSQL_DB:-hmdp}"

PHONE_A="${PHONE_A:-13686869696}"   # 用户 1
PHONE_B="${PHONE_B:-13838411438}"   # 用户 2
CODE="123456"

RESULT_DIR="$(cd "$(dirname "$0")/.." && pwd)/test-results/api"
mkdir -p "$RESULT_DIR"
LOG_FILE="$RESULT_DIR/api-crud-smoke-$(date +%Y%m%d_%H%M%S).log"

PASS=0
FAIL=0
TOKEN_A=""
TOKEN_B=""
CREATED_BLOG_ID=""
CREATED_COMMENT_ID=""
CREATED_FEEDBACK_ID=""
CREATED_ORDER_ID=""
INFO_A_EXISTED=""
INFO_B_EXISTED=""
FANS_B_BEFORE=""
MSG_MAX_ID_BEFORE=""

# ---------------------------------------------------------------- 输出工具
c_reset='\033[0m'; c_green='\033[32m'; c_red='\033[31m'; c_cyan='\033[36m'
log()     { echo -e "$*" | tee -a "$LOG_FILE"; }
section() { log "\n${c_cyan}=== $* ===${c_reset}"; }

ok()   { PASS=$((PASS+1)); log "  ${c_green}✔${c_reset} $1"; }
bad()  { FAIL=$((FAIL+1)); log "  ${c_red}✘${c_reset} $1  (期望: $2, 实际: $3)"; }

assert_eq() { # name expected actual
  if [ "$2" == "$3" ]; then ok "$1"; else bad "$1" "$2" "$3"; fi
}
assert_contains() { # name expected_substr actual
  case "$3" in *"$2"*) ok "$1";; *) bad "$1" "包含 $2" "$3";; esac
}

# ---------------------------------------------------------------- 基础设施
mysql_exec() {
  local out
  out="$(docker exec "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -D "$MYSQL_DB" -N -e "$1" 2>&1 || true)"
  if printf '%s' "$out" | grep -qi 'ERROR'; then
    printf '[mysql-error] %s\n' "$(printf '%s' "$out" | tr '\n' ' ')" >> "$LOG_FILE"
    printf ''
    return 0
  fi
  printf '%s' "$out" | grep -v 'Using a password' || true
}
redis_exec() {
  local out
  if [ -n "$REDIS_PASSWORD" ]; then
    out="$(docker exec "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASSWORD" --no-auth-warning "$@" 2>&1)" || true
  else
    out="$(docker exec "$REDIS_CONTAINER" redis-cli "$@" 2>&1)" || true
  fi
  case "$out" in
    *NOAUTH*|*WRONGPASS*|*NOAUTH\ Authentication*)
      printf '[redis-error: %s] %s\n' "$*" "$(printf '%s' "$out" | tr '\n' ' ')" >> "$LOG_FILE"
      return 1 ;;
  esac
}

http_code() { curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$@"; }
body()      { curl -s --max-time 10 "$@"; }

require_env() {
  command -v jq >/dev/null 2>&1 || { echo "需要 jq，请先安装"; exit 1; }
  if ! curl -s -o /dev/null --max-time 5 "$BASE_URL/actuator/health"; then
    echo "后端不可达：$BASE_URL（请先 docker compose up -d）"; exit 1
  fi
}

login() { # phone -> token
  local phone="$1"
  redis_exec set "login:code:${phone}" "$CODE" EX 300
  body -X POST "$BASE_URL/users/login" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"${phone}\",\"code\":\"${CODE}\"}" | jq -r '.data.token // empty'
}

prepare() {
  TOKEN_A="$(login "$PHONE_A")"
  TOKEN_B="$(login "$PHONE_B")"
  if [ -z "$TOKEN_A" ] || [ -z "$TOKEN_B" ]; then
    echo "登录失败，无法继续"; exit 1
  fi
  # 记录「用户 1 关注前是否已有 user_info 行」，避免清理时误删种子资料
  INFO_A_EXISTED="$(mysql_exec "SELECT COUNT(*) FROM tb_user_info WHERE user_id=1;")"
  # 记录消息表当前最大 id：关注动作会写站内信，收尾时按 id 精确回收
  MSG_MAX_ID_BEFORE="$(mysql_exec "SELECT COALESCE(MAX(id),0) FROM tb_message;")"
  # 关注会把「被关注者」的 fans +1 并 upsert 其 user_info，同样需要快照后还原
  INFO_B_EXISTED="$(mysql_exec "SELECT COUNT(*) FROM tb_user_info WHERE user_id=2;")"
  FANS_B_BEFORE="$(mysql_exec "SELECT COALESCE(fans,0) FROM tb_user_info WHERE user_id=2;")"
}

cleanup() {
  # 清理本次脚本产生的数据（失败中断也能执行）
  [ -n "$CREATED_ORDER_ID" ] && mysql_exec "DELETE FROM tb_voucher_order WHERE id=${CREATED_ORDER_ID};"
  [ -n "$CREATED_BLOG_ID" ] && {
    mysql_exec "DELETE FROM tb_blog_comments WHERE blog_id=${CREATED_BLOG_ID};"
    mysql_exec "DELETE FROM tb_blog WHERE id=${CREATED_BLOG_ID};"
    redis_exec del "blog:liked:${CREATED_BLOG_ID}"
  }
  [ -n "$CREATED_COMMENT_ID" ] && mysql_exec "DELETE FROM tb_shop_comments WHERE id=${CREATED_COMMENT_ID};"
  [ -n "$CREATED_FEEDBACK_ID" ] && mysql_exec "DELETE FROM tb_feedback WHERE id=${CREATED_FEEDBACK_ID};"
  mysql_exec "DELETE FROM tb_shop_comments WHERE content LIKE 'API-SMOKE-%';"
  mysql_exec "DELETE FROM tb_blog WHERE title LIKE 'API-SMOKE-%';"
  mysql_exec "DELETE FROM tb_feedback WHERE content LIKE 'API-SMOKE-%';"
  # 取关并清理浏览历史
  [ -n "$TOKEN_A" ] && {
    body -X DELETE "$BASE_URL/follows/2" -H "authorization: $TOKEN_A" >/dev/null
    body -X DELETE "$BASE_URL/browse-history" -H "authorization: $TOKEN_A" >/dev/null
  }
  # 物理清掉本次关注/取关留下的逻辑删除行（活跃关注关系不会被匹配）
  mysql_exec "DELETE FROM tb_follow WHERE user_id=1 AND follow_user_id=2 AND is_deleted=1;"
  # 回收本次关注动作写入的站内信
  if [ -n "${MSG_MAX_ID_BEFORE:-}" ]; then
    mysql_exec "DELETE FROM tb_message WHERE id > ${MSG_MAX_ID_BEFORE} AND sender_id=1;"
  fi
  # 仅当关注前用户 1 本就没有 user_info 行时，才删除本次关注/取关产生的残留行
  if [ "${INFO_A_EXISTED:-1}" == "0" ]; then
    mysql_exec "DELETE FROM tb_user_info WHERE user_id=1 AND COALESCE(fans,0)=0 AND COALESCE(followee,0)=0 AND introduce IS NULL;"
  fi
  # 还原被关注者（用户 2）的粉丝数 / user_info 行
  if [ "${INFO_B_EXISTED:-1}" == "0" ]; then
    mysql_exec "DELETE FROM tb_user_info WHERE user_id=2 AND COALESCE(fans,0)=0 AND COALESCE(followee,0)=0 AND introduce IS NULL;"
  elif [ -n "${FANS_B_BEFORE:-}" ]; then
    mysql_exec "UPDATE tb_user_info SET fans=${FANS_B_BEFORE} WHERE user_id=2;"
  fi
}
trap cleanup EXIT

# ============================================================================
require_env
prepare

log "接口回归脚本开始：$(date '+%F %T')"
log "BASE_URL=$BASE_URL"

# ------------------------------------------------------- 1. 认证与权限边界
section "1. 认证与权限边界"
assert_eq "匿名 GET /shops/1 可读"            "200" "$(http_code "$BASE_URL/shops/1")"
assert_eq "匿名 GET /shops?typeId=1 可读"      "200" "$(http_code "$BASE_URL/shops?typeId=1")"
assert_eq "匿名 GET /vouchers?shopId=1 可读"   "200" "$(http_code "$BASE_URL/vouchers?shopId=1")"
assert_eq "匿名 GET /shop-types 可读"          "200" "$(http_code "$BASE_URL/shop-types")"
assert_eq "匿名 GET /users/2/profile 可读"     "200" "$(http_code "$BASE_URL/users/2/profile")"
assert_eq "匿名 GET /users/2/blogs 可读"       "200" "$(http_code "$BASE_URL/users/2/blogs")"
assert_eq "匿名 GET /shops/1/comments 可读"    "200" "$(http_code "$BASE_URL/shops/1/comments")"
assert_eq "匿名 GET /shops/1/comments/summary" "200" "$(http_code "$BASE_URL/shops/1/comments/summary")"

assert_eq "匿名 POST /shops 被拒 401"          "401" "$(http_code -X POST "$BASE_URL/shops" -H 'Content-Type: application/json' -d '{"name":"x","typeId":1}')"
assert_eq "匿名 PUT /shops/1 被拒 401"         "401" "$(http_code -X PUT "$BASE_URL/shops/1" -H 'Content-Type: application/json' -d '{"name":"x","typeId":1}')"
assert_eq "匿名 POST /vouchers 被拒 401"       "401" "$(http_code -X POST "$BASE_URL/vouchers" -H 'Content-Type: application/json' -d '{"shopId":1,"title":"x","payValue":1,"actualValue":2,"type":0}')"
assert_eq "匿名 GET /users/me 被拒 401"        "401" "$(http_code "$BASE_URL/users/me")"
assert_eq "匿名 POST /messages/read-all 被拒 401" "401" "$(http_code -X POST "$BASE_URL/messages/read-all")"
assert_eq "匿名 GET /voucher-orders/1/status 被拒 401" "401" "$(http_code "$BASE_URL/voucher-orders/1/status")"

# ------------------------------------------------------- 2. 笔记内容管理
section "2. 笔记内容管理（新增/编辑/越权/删除）"
BLOG_JSON=$(body -X POST "$BASE_URL/blogs" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"title":"API-SMOKE-笔记","content":"API-SMOKE-内容","images":[],"shopId":1}')
CREATED_BLOG_ID="$(echo "$BLOG_JSON" | jq -r '.data // empty')"
assert_contains "作者发布笔记成功" "true" "$(echo "$BLOG_JSON" | jq -r '.success')"

if [ -n "$CREATED_BLOG_ID" ]; then
  assert_eq "作者编辑笔记成功" "true" \
    "$(body -X PUT "$BASE_URL/blogs/$CREATED_BLOG_ID" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
      -d '{"title":"API-SMOKE-笔记(已编辑)","content":"API-SMOKE-已编辑","images":["/imgs/a.jpg"],"shopId":1}' | jq -r '.success')"
  assert_eq "非作者编辑按 20001 应答" "20001" \
    "$(body -X PUT "$BASE_URL/blogs/$CREATED_BLOG_ID" -H "authorization: $TOKEN_B" -H 'Content-Type: application/json' \
      -d '{"title":"越权","content":"越权","images":[],"shopId":1}' | jq -r '.errorCode')"
  assert_eq "非作者删除按 20001 应答" "20001" \
    "$(body -X DELETE "$BASE_URL/blogs/$CREATED_BLOG_ID" -H "authorization: $TOKEN_B" | jq -r '.errorCode')"
  assert_eq "作者删除笔记成功" "true" \
    "$(body -X DELETE "$BASE_URL/blogs/$CREATED_BLOG_ID" -H "authorization: $TOKEN_A" | jq -r '.success')"
  assert_eq "删除后详情返回 20001" "20001" \
    "$(body "$BASE_URL/blogs/$CREATED_BLOG_ID" -H "authorization: $TOKEN_A" | jq -r '.errorCode')"
  CREATED_BLOG_ID=""
fi

# ------------------------------------------------------- 3. 商铺评价
section "3. 商铺评价（发布/编辑/越权/边界/删除）"
COMMENT_JSON=$(body -X POST "$BASE_URL/shop-comments" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"shopId":1,"content":"API-SMOKE-评价","rating":5}')
CREATED_COMMENT_ID="$(echo "$COMMENT_JSON" | jq -r '.data // empty')"
assert_contains "发布评价成功" "true" "$(echo "$COMMENT_JSON" | jq -r '.success')"

if [ -n "$CREATED_COMMENT_ID" ]; then
  assert_eq "作者编辑评价成功" "true" \
    "$(body -X PUT "$BASE_URL/shop-comments/$CREATED_COMMENT_ID" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
      -d '{"content":"API-SMOKE-评价(已编辑)","rating":4}' | jq -r '.success')"
  assert_eq "非作者编辑按 40002 应答" "40002" \
    "$(body -X PUT "$BASE_URL/shop-comments/$CREATED_COMMENT_ID" -H "authorization: $TOKEN_B" -H 'Content-Type: application/json' \
      -d '{"content":"越权","rating":1}' | jq -r '.errorCode')"
  assert_eq "评分越界被拦截 10008" "10008" \
    "$(body -X PUT "$BASE_URL/shop-comments/$CREATED_COMMENT_ID" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
      -d '{"content":"x","rating":9}' | jq -r '.errorCode')"
  assert_eq "评价不存在的店铺返回 40001" "40001" \
    "$(body -X POST "$BASE_URL/shop-comments" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
      -d '{"shopId":999999999,"content":"API-SMOKE-未知店铺","rating":5}' | jq -r '.errorCode')"
  assert_eq "作者删除评价成功" "true" \
    "$(body -X DELETE "$BASE_URL/shop-comments/$CREATED_COMMENT_ID" -H "authorization: $TOKEN_A" | jq -r '.success')"
  CREATED_COMMENT_ID=""
fi

# ------------------------------------------------------- 4. 意见反馈
section "4. 意见反馈（提交/列表/越权/删除）"
FEEDBACK_JSON=$(body -X POST "$BASE_URL/feedbacks" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
  -d '{"content":"API-SMOKE-反馈内容","type":1}')
CREATED_FEEDBACK_ID="$(echo "$FEEDBACK_JSON" | jq -r '.data // empty')"
assert_contains "提交反馈成功" "true" "$(echo "$FEEDBACK_JSON" | jq -r '.success')"

if [ -n "$CREATED_FEEDBACK_ID" ]; then
  assert_eq "我的反馈列表可查" "true" "$(body "$BASE_URL/feedbacks/mine" -H "authorization: $TOKEN_A" | jq -r '.success')"
  assert_eq "越权删除反馈被拒" "反馈不存在或无权操作" \
    "$(body -X DELETE "$BASE_URL/feedbacks/$CREATED_FEEDBACK_ID" -H "authorization: $TOKEN_B" | jq -r '.errorMsg')"
  assert_eq "本人删除反馈成功" "true" \
    "$(body -X DELETE "$BASE_URL/feedbacks/$CREATED_FEEDBACK_ID" -H "authorization: $TOKEN_A" | jq -r '.success')"
  CREATED_FEEDBACK_ID=""
fi

# ------------------------------------------------------- 5. 消息全部已读
section "5. 消息（全部已读）"
assert_eq "全部已读接口可用" "true" \
  "$(body -X POST "$BASE_URL/messages/read-all" -H "authorization: $TOKEN_A" | jq -r '.success')"
assert_eq "重复调用幂等（仍为 true）" "true" \
  "$(body -X POST "$BASE_URL/messages/read-all" -H "authorization: $TOKEN_A" | jq -r '.success')"

# ------------------------------------------------------- 6. 浏览历史
section "6. 浏览历史（去重/边界/清空）"
assert_eq "上报店铺浏览成功" "true" \
  "$(body -X POST "$BASE_URL/browse-history" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
    -d '{"targetType":1,"targetId":1}' | jq -r '.success')"
assert_eq "重复上报店铺仍成功（去重）" "true" \
  "$(body -X POST "$BASE_URL/browse-history" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
    -d '{"targetType":1,"targetId":1}' | jq -r '.success')"
assert_eq "上报笔记浏览成功" "true" \
  "$(body -X POST "$BASE_URL/browse-history" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
    -d '{"targetType":2,"targetId":4}' | jq -r '.success')"
assert_eq "非法 targetType 返回 10008" "10008" \
  "$(body -X POST "$BASE_URL/browse-history" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
    -d '{"targetType":3,"targetId":1}' | jq -r '.errorCode')"
assert_eq "目标不存在返回 40001" "40001" \
  "$(body -X POST "$BASE_URL/browse-history" -H "authorization: $TOKEN_A" -H 'Content-Type: application/json' \
    -d '{"targetType":1,"targetId":999999999}' | jq -r '.errorCode')"
assert_eq "历史列表可查" "true" "$(body "$BASE_URL/browse-history/mine" -H "authorization: $TOKEN_A" | jq -r '.success')"
assert_eq "清空历史成功" "true" \
  "$(body -X DELETE "$BASE_URL/browse-history" -H "authorization: $TOKEN_A" | jq -r '.success')"
assert_eq "清空后历史为空" "0" \
  "$(body "$BASE_URL/browse-history/mine" -H "authorization: $TOKEN_A" | jq -r '.data | length')"

# ------------------------------------------------------- 7. 社交闭环
section "7. 社交闭环（关注 → 他人主页 → 取关）"
assert_eq "关注用户 2 成功" "true" \
  "$(body -X POST "$BASE_URL/follows/2" -H "authorization: $TOKEN_A" | jq -r '.success')"
assert_eq "重复关注幂等" "true" \
  "$(body -X POST "$BASE_URL/follows/2" -H "authorization: $TOKEN_A" | jq -r '.success')"
assert_eq "他人主页 followedByMe=true" "true" \
  "$(body "$BASE_URL/users/2/profile" -H "authorization: $TOKEN_A" | jq -r '.data.followedByMe')"
assert_eq "匿名访问同一主页 followedByMe=false" "false" \
  "$(body "$BASE_URL/users/2/profile" | jq -r '.data.followedByMe')"
assert_eq "取关成功" "true" \
  "$(body -X DELETE "$BASE_URL/follows/2" -H "authorization: $TOKEN_A" | jq -r '.success')"
assert_eq "取关后 followedByMe=false" "false" \
  "$(body "$BASE_URL/users/2/profile" -H "authorization: $TOKEN_A" | jq -r '.data.followedByMe')"
assert_eq "不能关注自己 10005" "10005" \
  "$(body -X POST "$BASE_URL/follows/1" -H "authorization: $TOKEN_A" | jq -r '.errorCode')"

# ------------------------------------------------------- 8. 订单状态归属
section "8. 订单状态归属校验（越权按不存在应答）"
CREATED_ORDER_ID=8888888888888001
# 该占位订单使用 (user_id=1, voucher_id=1)，需先清掉同键残留，否则会撞 uk_user_voucher
mysql_exec "DELETE FROM tb_voucher_order WHERE id=${CREATED_ORDER_ID} OR (user_id=1 AND voucher_id=1);"
mysql_exec "INSERT INTO tb_voucher_order (id, user_id, voucher_id, pay_type, status) VALUES (${CREATED_ORDER_ID}, 1, 1, 1, 1);"
assert_eq "占位订单已写入 DB" "1" "$(mysql_exec "SELECT COUNT(*) FROM tb_voucher_order WHERE id=${CREATED_ORDER_ID};")"
assert_eq "本人查询返回真实状态 1" "1" \
  "$(body "$BASE_URL/voucher-orders/${CREATED_ORDER_ID}/status" -H "authorization: $TOKEN_A" | jq -r '.data')"
assert_eq "他人查询按不存在应答 -1" "-1" \
  "$(body "$BASE_URL/voucher-orders/${CREATED_ORDER_ID}/status" -H "authorization: $TOKEN_B" | jq -r '.data')"
assert_eq "不存在订单返回 -1" "-1" \
  "$(body "$BASE_URL/voucher-orders/123456789/status" -H "authorization: $TOKEN_A" | jq -r '.data')"
mysql_exec "DELETE FROM tb_voucher_order WHERE id=${CREATED_ORDER_ID};"
CREATED_ORDER_ID=""

# ------------------------------------------------------- 9. 分页上限
section "9. 分页上限（size 超限被截断）"
mysql_exec "INSERT INTO tb_shop_comments (shop_id, user_id, content, rating) SELECT 1,1,CONCAT('API-SMOKE-', @r:=@r+1), 5 FROM information_schema.columns c1, (SELECT @r:=0) t LIMIT 150;"
CAP_COUNT="$(body "$BASE_URL/shops/1/comments?current=1&size=1000" | jq -r '.data | length')"
if [ "$CAP_COUNT" -le 100 ]; then ok "size=1000 被截断为 $CAP_COUNT (<=100)"; else bad "分页上限未生效" "<=100" "$CAP_COUNT"; fi
mysql_exec "DELETE FROM tb_shop_comments WHERE content LIKE 'API-SMOKE-%';"

# ------------------------------------------------------- 汇总
section "结果汇总"
log "  通过：${PASS}"
log "  失败：${FAIL}"
log "  日志：${LOG_FILE}"
log "  结束：$(date '+%F %T')"

[ "$FAIL" -eq 0 ]
