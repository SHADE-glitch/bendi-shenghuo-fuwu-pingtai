import client from './client';
import { Result, MerchantShopDTO, MerchantShopWriteRequest } from './types';

/**
 * 对应后端 ShopController 的商家侧店铺接口（Phase 4.6）
 *
 * - `GET  /shops/my`        查询当前登录商家的「我的店铺」（不分页）
 * - `POST /shops`           新建店铺（ownerId 由后端从登录态注入）
 * - `PUT  /shops/{shopId}`  更新自己的店铺（后端 MerchantShopGuard.assertOwner 强制归属）
 *
 * 查询范围与归属一律由后端按登录态推导：前端**不传** ownerId。
 * 匿名 401+10001 / 非商家 403+40003 / 非本店 40004 均由后端强制，
 * 前端 `requiresMerchant` 守卫仅做 UX 收敛，不承担安全职责。
 *
 * 未使用 Idempotency-Key：与既有「普通实体 CRUD」（`shopComment` / `feedback`）一致——
 * 幂等头在既有代码里只用于**订单状态流转**类写操作（pay / cancel / refund / redeem /
 * seckill 等）。防重复提交由页面的 mutation pending 守卫负责。
 */
export const merchantShopService = {
  /**
   * 查询当前登录商家的店铺列表
   * GET /shops/my
   *
   * 后端走 `Result.ok(data)`（不设 total），因此 `Result.total === null` → 不分页。
   */
  queryMyShops() {
    return client.get<Result<MerchantShopDTO[]>>('/shops/my');
  },

  /**
   * 新建店铺
   * POST /shops
   *
   * `tb_shop` 的 `images` / `sold` / `comments` / `score` 为 **NOT NULL 且无 DEFAULT**
   * （Phase 4.6 Step 1 已用真实请求逐个验证：省略任一个 → MySQL 1364
   * "Field 'xxx' doesn't have a default value" → 后端兜底为 99999）。
   * 这 4 个都是服务端管理字段，表单不暴露，由此处统一补齐。
   */
  createShop(payload: MerchantShopWriteRequest) {
    return client.post<Result<number | string>>('/shops', {
      ...payload,
      images: '[]',
      sold: 0,
      comments: 0,
      score: 0,
    });
  },

  /**
   * 更新自己的店铺
   * PUT /shops/{shopId}
   *
   * 后端 `updateById` 只更新非 null 字段；此处**不提交**
   * `ownerId` / `sold` / `comments` / `score` / `images`，使这些字段保持后端原值。
   */
  updateShop(shopId: number | string, payload: MerchantShopWriteRequest) {
    return client.put<Result<void>>(`/shops/${shopId}`, payload);
  },
};
