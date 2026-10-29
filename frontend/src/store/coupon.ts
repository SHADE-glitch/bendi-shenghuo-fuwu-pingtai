import { defineStore } from 'pinia';
import { ref } from 'vue';
import type { MyVoucherItemDTO, Voucher } from '@/api/types';

/**
 * 优惠券状态：某商铺优惠券列表 + 我的优惠券订单缓存
 */
export const useCouponStore = defineStore('coupon', () => {
  // ---- state ----
  const vouchers = ref<Voucher[]>([]);
  const myVouchers = ref<MyVoucherItemDTO[]>([]);
  const total = ref(0);

  // ---- actions ----
  function setVouchers(list: Voucher[]) {
    vouchers.value = list;
  }

  function setMyVouchers(list: MyVoucherItemDTO[], totalCount = 0) {
    myVouchers.value = list;
    total.value = totalCount;
  }

  function reset() {
    vouchers.value = [];
    myVouchers.value = [];
    total.value = 0;
  }

  return {
    vouchers,
    myVouchers,
    total,
    setVouchers,
    setMyVouchers,
    reset,
  };
});
