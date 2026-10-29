import client from './client';
import { Result, Voucher, CreateVoucherOrderResultDTO } from './types';
import { idempotencyHeaders } from '@/utils/idempotency';

/**
 * 对应后端 VoucherController（优惠券查询与下单）
 * 订单的查询与状态流转见 orderService。
 */
export const voucherService = {
  /**
   * 查询商铺的优惠券列表
   * GET /vouchers?shopId={shopId}
   */
  queryVoucherOfShop(shopId: number | string) {
    return client.get<Result<Voucher[]>>('/vouchers', { params: { shopId } });
  },

  /**
   * 秒杀下单 (异步模式)
   * POST /voucher-orders/seckill-orders?voucherId={voucherId}
   */
  seckill(voucherId: number | string) {
    return client.post<Result<CreateVoucherOrderResultDTO>>(
      '/voucher-orders/seckill-orders',
      null,
      { params: { voucherId }, headers: idempotencyHeaders(`seckill:${voucherId}`) },
    );
  },

  /**
   * 普通券购买 (同步模式)
   * POST /voucher-orders/purchase-orders?voucherId={voucherId}
   */
  purchase(voucherId: number | string) {
    return client.post<Result<CreateVoucherOrderResultDTO>>(
      '/voucher-orders/purchase-orders',
      null,
      { params: { voucherId }, headers: idempotencyHeaders(`purchase:${voucherId}`) },
    );
  },
};
