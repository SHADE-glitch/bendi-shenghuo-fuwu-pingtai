# backend/ — map of the Local-life backend

A single Spring Boot 3.3 / Java 17 application (`hm-dianping`): the consumer shop feed, the voucher
and seckill order flow, the merchant console APIs and the ordinary CRUD surface. Modular monolith —
one deployment unit, MySQL 8 + Redis 7 + Redisson.

> This file is a map, not a policy.

## Runtime

| Item | Value |
|---|---|
| Port | `8081` (`SERVER_PORT`) |
| Datastores | MySQL 8 (Flyway `V1`–`V17`), Redis 7.2 with `requirepass` |
| Build | Maven inside the container (`target/` is owned by container root) |
| Health | `GET /actuator/health`, `GET /health`, `GET /ping` |
| Auth header | `authorization`, lowercase, **no** `Bearer` prefix |

## Package map

`com.hmdp`

| Package | Responsibility | Key classes |
|---|---|---|
| `controller` | HTTP surface (19 controllers) | `Shop`, `ShopType`, `Voucher`, `VoucherOrder`, `Blog` + `BlogManage`, `BlogComment`, `ShopComment` + `ShopCommentManage`, `Follow`, `Message` + `MessageManage`, `Feedback` + `FeedbackManage`, `BrowseHistory`, `User`, `UserProfile`, `Upload`, `Health` |
| `service` + `service/impl` | Business logic; interface per aggregate | `ShopServiceImpl`, `VoucherOrderServiceImpl`, `VoucherServiceImpl`, `SeckillVoucherServiceImpl`, `MerchantVoucherOrderQueryServiceImpl`, `MerchantVoucherRedeemServiceImpl`, `UserServiceImpl`, `FileStorageServiceImpl` |
| `utils` | Cache client, locks, id generation, interceptors, stock compensation | `CacheClient`, `RedisIdWorker`, `RedisConstants`, `SeckillStockCompensator`, `IdempotencyInterceptor`, `RefreshTokenInterceptor`, `LoginInterceptor`, `WriteAuthInterceptor`, `TraceInterceptor`, `UserHolder` |
| `security` | Merchant identity and shop ownership | `MerchantShopGuard`, `MerchantAccessException` |
| `config` | MVC, MyBatis, Redisson, graceful shutdown, metrics, GEO preload | `MvcConfig`, `MybatisConfig`, `RedissonConfig`, `GracefulShutdownConfig`, `MetricsConfig`, `ShopGeoPreloadRunner`, `WebExceptionAdvice` |
| `entity` / `mapper` / `dto` | Persistence and transport | 14 entities, 14 mappers, 31 DTOs |

## HTTP surface

| Group | Paths |
|---|---|
| Shops | `GET /shops/{id}`, `GET /shops` (type + geo paging), `GET /shops/my`, `POST /shops`, `PUT /shops/{id}` |
| Shop types | `GET /shop-types` |
| Vouchers | `POST /vouchers`, `POST /vouchers/seckill-vouchers`, `GET /vouchers`, `GET /vouchers/my`, `PUT /vouchers/{id}` |
| Orders | `POST /voucher-orders/seckill-orders`, `POST /voucher-orders/purchase-orders`, `GET /voucher-orders/{id}`, `/{id}/status`, `/mine`, `/merchant`, pay / cancel / refund / delete / redeem |
| Blog | `/blogs` create, detail, by shop, like / unlike, mine; `/blogs/{id}` update + delete (manage) |
| Comments | `/blogs/{blogId}/comments`, `/shops/{shopId}/comments`, `/shop-comments` CRUD + summary |
| Social | `/follows` follow / unfollow / following / followers |
| Messages | `/messages/list`, `/messages/read/{id}`, `/messages/unread/count` |
| Feedback | `/feedbacks` create, `/feedbacks/mine` |
| User | `/users` code + login, `/users/me`, profile update, browse history |
| Upload | `POST /upload/icon` |

## Data model (Flyway `V1`–`V17`)

`tb_user`, `tb_user_info`, `tb_shop`, `tb_shop_type`, `tb_voucher`, `tb_seckill_voucher`,
`tb_voucher_order`, `tb_blog`, `tb_blog_comments`, `tb_shop_comments`, `tb_follow`,
`tb_message`, `tb_feedback`, `tb_browse_history`, `tb_sign`.

`V17` turns the one-order-per-user key into `uk_user_voucher_active` over the generated column
`active_voucher_id` (NULL for cancelled/refunded, so a terminal order stops holding the slot).

New migrations start at `V18__*.sql`; applied ones are immutable — changing a released file breaks
the checksum of every database that already ran it, so `baseline-version` (not a rewrite) is how a
restored schema is handled. See `MAINTENANCE.md`.

## Redis key map

| Key | Purpose |
|---|---|
| `login:code:{phone}` | SMS code, 2 min |
| `login:token:{token}` | Session hash, 10 h sliding |
| `cache:shop:{id}` / `cache:shopType:` | Cache, 30 min + 0–300 s jitter; null cache 2 s |
| `lock:shop:{id}` | Cache rebuild mutex |
| `lock:seckill:order:{userId}:{voucherId}` / `lock:order:...` | Per-user order locks |
| `seckill:stock:{voucherId}` | Pre-deducted stock (Lua) |
| `seckill:orders:{voucherId}` | Claimed-user set (Lua `SISMEMBER`) |
| `stream:seckill:orders` + `seckill-group` | Async order persistence (consumer group + PEL) |
| `shop:geo:{typeId}:{city}` | GEO index, warmed at startup and re-synced on shop create/update |
| `icr:{prefix}:{yyyy:MM:dd}` | Daily id sequence — **never delete** |
| `idem:req:{user}:{method}:{uri}:{key}` | Idempotency reservation, 5 min |

## Tests

| Layer | Location | Command |
|---|---|---|
| JUnit | `src/test/java` (the count is printed by the command) | `docker exec Local-life-service-platform-backend sh -c 'cd /app && mvn -B test'` |
| API smoke | `backend/tests/api-crud-smoke.sh` (52 checks) | needs a running backend |
| API suite | `backend/tests/api-test-suite.sh` | needs a running backend |
| Seckill concurrency | `backend/tests/seckill_concurrency_final.py` | writes `test-results/verify/` — do not re-run over the artifact |

## Traps specific to this backend

- The container runs a resident `mvn compile` loop. After editing Java, `health=UP` and a fresh
  `target/classes` timestamp still do not prove the bytecode contains your change — verify with
  `javap` plus a per-method probe.
- Redis runs with `requirepass`; every script needs `REDIS_PASSWORD`, and the frozen seckill script
  needs `REDISCLI_AUTH` instead.
- `/users/code/mock` is disabled; real codes are in the container log.
- Backend paths carry no `/api` prefix (that is the Vite proxy).

## Reading order

1. `VoucherOrderController` → `VoucherOrderServiceImpl.seckillVoucher` → `seckill.lua` → the Stream consumer.
   The reverse direction is `cancelVoucherOrder` / `refundVoucherOrder` → `SeckillStockCompensator`
   (after commit, `seckill_rollback.lua`, idempotent per order).
2. `ShopServiceImpl.queryById` + `CacheClient` — the cache path.
3. `MvcConfig` → `RefreshTokenInterceptor` → `LoginInterceptor` → `WriteAuthInterceptor` — the auth chain.
4. `MerchantShopGuard` — merchant ownership.
