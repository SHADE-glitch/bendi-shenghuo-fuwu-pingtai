import client from './client';
import {
  Result,
  OrderStatus,
  PayType,
  MyVoucherItemDTO,
  VoucherOrderDetailDTO,
  MyVoucherTab,
} from './types';
import { idempotencyHeaders } from '@/utils/idempotency';

/**
 * 对应后端 VoucherOrderController（优惠券订单）
 * 与 voucherService 职责分离：voucherService 负责优惠券查询与下单，orderService 负责订单查询与状态流转。
 */
export const orderService = {
  /**
   * 查询订单状态
   * GET /voucher-orders/{orderId}/status
   */
  queryOrderStatus(orderId: number | string) {
    return client.get<Result<OrderStatus>>(`/voucher-orders/${orderId}/status`);
  },

  /**
   * 查询我的优惠券订单（分页）
   * GET /voucher-orders/mine?tab=unused|used|expired|invalid&current=1&size=10
   */
  queryMyVouchers(tab: MyVoucherTab, current = 1, size = 10) {
    return client.get<Result<MyVoucherItemDTO[]>>('/voucher-orders/mine', {
      params: { tab, current, size },
    });
  },

  /**
   * 查询订单详情
   * GET /voucher-orders/{orderId}
   */
  queryOrderDetail(orderId: number | string) {
    return client.get<Result<VoucherOrderDetailDTO>>(`/voucher-orders/${orderId}`);
  },

  /**
   * 伪支付：模拟余额/微信/支付宝支付待支付订单
   * POST /voucher-orders/{orderId}/pay?payType=
   */
  pay(orderId: number | string, payType: PayType | number) {
    return client.post<Result<void>>(`/voucher-orders/${orderId}/pay`, null, {
      params: { payType },
      headers: idempotencyHeaders(`pay:${orderId}:${payType}`),
    });
  },

  /**
   * 取消待支付订单
   * POST /voucher-orders/{orderId}/cancel
   */
  cancel(orderId: number | string) {
    return client.post<Result<void>>(`/voucher-orders/${orderId}/cancel`, null, {
      headers: idempotencyHeaders(`cancel:${orderId}`),
    });
  },

  /**
   * 退款：已支付未核销订单退款并恢复库存
   * POST /voucher-orders/{orderId}/refund
   */
  refund(orderId: number | string) {
    return client.post<Result<void>>(`/voucher-orders/${orderId}/refund`, null, {
      headers: idempotencyHeaders(`refund:${orderId}`),
    });
  },

  /**
   * 删除已取消/已退款订单记录
   * DELETE /voucher-orders/{orderId}
   */
  removeOrder(orderId: number | string) {
    return client.delete<Result<void>>(`/voucher-orders/${orderId}`, {
      headers: idempotencyHeaders(`remove:${orderId}`),
    });
  },
};
