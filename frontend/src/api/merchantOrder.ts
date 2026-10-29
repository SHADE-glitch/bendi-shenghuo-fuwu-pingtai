import client from './client';
import { Result, MerchantVoucherOrderItemDTO, OrderStatus } from './types';
import { idempotencyHeaders } from '@/utils/idempotency';

/**
 * 对应后端 VoucherOrderController 的商家侧接口（Phase 3.4-B）
 *
 * - `GET  /voucher-orders/merchant`         分页查询「本店订单」（B.3）
 * - `POST /voucher-orders/{orderId}/redeem` 核销订单 PAID(2) → USED(3)（3.4-A）
 *
 * 说明：查询范围由后端按登录商家 `owner_id` 推导，前端**不传** shopId/voucherId；
 * 匿名 401 + 10001、非商家 403 + 40003、跨店 403 + 40004 等均由后端强制，
 * 前端 `requiresMerchant` 守卫仅做 UX 层收敛，不承担安全职责。
 */
export const merchantOrderService = {
  /**
   * 分页查询本店订单
   * GET /voucher-orders/merchant?current=1&size=10[&status=2]
   *
   * @param status 可选状态过滤；传 `null`/`undefined` 表示不过滤（后端按缺省处理）
   */
  queryMerchantOrders(
    current = 1,
    size = 10,
    status?: OrderStatus | number | null
  ) {
    return client.get<Result<MerchantVoucherOrderItemDTO[]>>('/voucher-orders/merchant', {
      params: {
        current,
        size,
        ...(status === null || status === undefined ? {} : { status }),
      },
    });
  },

  /**
   * 核销订单（PAID → USED）
   * POST /voucher-orders/{orderId}/redeem
   *
   * 后端以 CAS（`status=2` 条件下更新）保证幂等，重复核销返回 30009；
   * 携带 Idempotency-Key 与既有 orderService 的写操作保持一致。
   */
  redeemMerchantOrder(orderId: number | string) {
    return client.post<Result<void>>(`/voucher-orders/${orderId}/redeem`, null, {
      headers: idempotencyHeaders(`redeem:${orderId}`),
    });
  },
};
