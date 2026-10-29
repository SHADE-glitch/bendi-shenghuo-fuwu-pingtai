import { voucherService } from '@/api/voucher';
import { useCouponStore } from '@/store/coupon';
import type { Voucher } from '@/api/types';

/**
 * 优惠券操作逻辑：查询商铺优惠券列表。
 */
export function useVoucher() {
  const couponStore = useCouponStore();

  const fetchVouchers = async (shopId: number | string): Promise<Voucher[]> => {
    const res = await voucherService.queryVoucherOfShop(shopId);
    const list = res.data.data || [];
    couponStore.setVouchers(list);
    return list;
  };

  return { couponStore, fetchVouchers };
}
