import client from './client';
import {
  Result,
  MerchantVoucherDTO,
  MerchantVoucherCreateRequest,
  MerchantVoucherUpdateRequest,
} from './types';

/**
 * 商家优惠券 API（对应后端 VoucherController 商家侧，Phase 4.7）
 *
 * - `GET  /vouchers/my`               查询当前商家的「我的优惠券」（不分页）
 * - `POST /vouchers`                  新增普通券（type=0）
 * - `POST /vouchers/seckill-vouchers` 新增秒杀券（type=1，前端必须显式发送）
 * - `PUT  /vouchers/{voucherId}`      更新自己的券（后端白名单 + MerchantShopGuard 强制归属）
 *
 * 查询范围与归属一律由后端按登录态推导：前端**不传** ownerId。
 * 匿名 401+10001 / 非商家 403+40003 / 券不存在 30000 / 非本店 40004 均由后端强制。
 *
 * 未使用 Idempotency-Key：与既有「普通实体 CRUD」（merchantShop / shopComment）一致，
 * 防重复提交由页面 mutation pending 守卫负责。
 */
export const merchantVoucherService = {
  /**
   * 查询当前商家的优惠券列表
   * GET /vouchers/my
   *
   * 后端 `Result.ok(data)` 不设 total → 不分页，`total === null`。
   * 返回当前商家名下**所有**店铺的券（含已下架/过期），不传分页参数。
   */
  queryMyVouchers() {
    return client.get<Result<MerchantVoucherDTO[]>>('/vouchers/my');
  },

  /**
   * 新增普通优惠券
   * POST /vouchers
   *
   * 后端 `addVoucher` 要求 `type === 0`，否则 30007。调用方负责构造 type=0。
   */
  createVoucher(payload: MerchantVoucherCreateRequest) {
    return client.post<Result<number | string>>('/vouchers', payload);
  },

  /**
   * 新增秒杀优惠券
   * POST /vouchers/seckill-vouchers
   *
   * 后端 `addSeckillVoucher` **不校验 type** —— 必须由前端显式发送 `type: 1`。
   * 缺 stock/beginTime/endTime 会因 tb_seckill_voucher 的 NOT NULL 而事务回滚。
   */
  createSeckillVoucher(payload: MerchantVoucherCreateRequest) {
    return client.post<Result<number | string>>('/vouchers/seckill-vouchers', payload);
  },

  /**
   * 更新自己的优惠券
   * PUT /vouchers/{voucherId}
   *
   * id 原样拼进 URL，**禁止** Number(id) / parseInt(id) —— Long 以字符串透传免丢精度。
   * 白名单字段见 MerchantVoucherUpdateRequest（title 为 @NotBlank，status-only 也要带 title）。
   */
  updateVoucher(voucherId: number | string, payload: MerchantVoucherUpdateRequest) {
    return client.put<Result<void>>(`/vouchers/${voucherId}`, payload);
  },
};