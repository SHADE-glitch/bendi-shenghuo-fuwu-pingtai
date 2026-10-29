import { orderService } from '@/api/orderService';
import { useCouponStore } from '@/store/coupon';
import type { MyVoucherItemDTO, MyVoucherTab } from '@/api/types';

/**
 * 订单操作逻辑：查询我的优惠券订单。
 */
export function useOrder() {
  const couponStore = useCouponStore();

  const fetchMyVouchers = async (
    tab: MyVoucherTab,
    current = 1,
    size = 20
  ): Promise<MyVoucherItemDTO[]> => {
    const res = await orderService.queryMyVouchers(tab, current, size);
    const list = res.data.data || [];
    couponStore.setMyVouchers(list, res.data.total || 0);
    return list;
  };

  return { couponStore, fetchMyVouchers };
}
