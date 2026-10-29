<script setup lang="ts">
import { ref, onMounted, watch, computed } from 'vue';
import { useRouter } from 'vue-router';
import { showToast } from 'vant';
import { MyVoucherItemDTO } from '@/api/types';
import { orderService } from '@/api/orderService';
import { formatDateTime, formatMoney, firstImage, orderStatusText } from '@/utils/format';
import { usePagination } from '@/composables/usePagination';
import { PAY_TYPE_OPTIONS, useVoucherOrderActions } from '@/composables/useVoucherOrderActions';

const router = useRouter();
const activeTab = ref(0);

// 券订单终态共 4 种：已使用(3)、已取消(4)、已过期(5)、已退款(6)。
// 「已过期(5)」的写入路径：用户对已过期的秒杀券发起支付时，
// payVoucherOrder 会先以 CAS 把 PENDING_PAYMENT 收敛到 EXPIRED 再返回 30010，
// 因此该单不会再永远卡在「未使用」里。
const tabs = ['unused', 'used', 'expired', 'invalid'] as const;
const currentTab = computed(() => tabs[activeTab.value] || 'unused');

/** 我的优惠券分页：不再写死只取 20 条，改为「加载更多」 */
const {
  list: vouchers,
  total,
  loading,
  finished,
  load,
  loadMore,
} = usePagination<MyVoucherItemDTO>(async ({ current, size }) => {
  try {
    const res = await orderService.queryMyVouchers(currentTab.value, current, size);
    return { list: res.data.data || [], total: Number(res.data.total || 0) };
  } catch (error: any) {
    showToast(error?.errorMsg || '加载失败');
    return { list: [], total: 0 };
  }
});

const fetchVouchers = () => load(true);

const { payDialog, openPayDialog, doPay, cancelOrder, refundOrder, deleteOrder } = useVoucherOrderActions(fetchVouchers);

const goBack = () => router.back();
const goToHome = () => router.push('/');
const goToDetail = (orderId: number | string) => router.push(`/order/detail/${orderId}`);
const handlePay = (voucher: MyVoucherItemDTO) => openPayDialog(voucher);
const handleCancel = (voucher: MyVoucherItemDTO) => cancelOrder(voucher);
const handleRefund = (voucher: MyVoucherItemDTO) => refundOrder(voucher);
const handleDelete = (voucher: MyVoucherItemDTO) => deleteOrder(voucher);

onMounted(fetchVouchers);
watch(activeTab, () => load(true));
</script>

<template>
  <div class="my-vouchers-page min-h-screen bg-gray-50">
    <van-nav-bar
      title="我的优惠券"
      left-arrow
      safe-area-inset-top
      fixed
      placeholder
      @click-left="goBack"
    />

    <!-- Tabs -->
    <van-tabs v-model:active="activeTab" class="" color="#f72d4e">
      <van-tab title="未使用" />
      <van-tab title="已使用" />
      <van-tab title="已过期" />
      <van-tab title="已取消/退款" />
    </van-tabs>

    <!-- Loading -->
    <div v-if="loading" class="p-20 text-center">
      <van-loading type="spinner" size="24px">加载中...</van-loading>
    </div>

    <!-- Empty -->
    <div v-else-if="vouchers.length === 0" class="p-20 text-center text-gray-400">
      <van-icon name="coupon-o" size="64px" class="mb-4" />
      <p>暂无优惠券</p>
      <van-button type="primary" size="small" round class="mt-6 px-8" @click="goToHome">
        去抢购
      </van-button>
    </div>

    <!-- Voucher List -->
    <div v-else class="p-4">
      <div class="text-xs text-gray-400 mb-2">共 {{ total }} 张</div>
      <div 
        v-for="voucher in vouchers" 
        :key="voucher.orderId" 
        class="voucher-card bg-white rounded-xl overflow-hidden shadow-sm mb-4 flex border border-gray-50 cursor-pointer"
        @click="goToDetail(voucher.orderId)"
      >
        <div class="w-24 bg-gradient-to-br from-orange-400 to-orange-500 text-white flex flex-col items-center justify-center p-2">
          <div class="text-2xl font-bold">¥{{ formatMoney(voucher.actualValue) }}</div>
          <div class="text-[10px] opacity-80">满{{ formatMoney(voucher.payValue) }}可用</div>
        </div>
        <div class="flex-1 p-4">
          <div v-if="voucher.shopName" class="flex items-center gap-1.5 mb-1">
            <van-image v-if="firstImage(voucher.shopImage)" round fit="cover" :src="firstImage(voucher.shopImage)" class="w-4 h-4 flex-shrink-0" />
            <span class="text-[11px] text-gray-500 font-medium truncate">{{ voucher.shopName }}</span>
          </div>
          <h3 class="font-bold text-gray-800">{{ voucher.title }}</h3>
          <p class="mt-1 text-xs text-gray-400 line-clamp-1">{{ voucher.rules || '全场通用' }}</p>
          <div class="mt-2 flex items-center gap-1 text-[11px] text-gray-400">
            <van-icon name="clock-o" />
            <span v-if="voucher.type === 1 && voucher.beginTime && voucher.endTime">{{ formatDateTime(voucher.beginTime) }} ~ {{ formatDateTime(voucher.endTime) }}</span>
            <span v-else>长期有效</span>
          </div>
          <div class="mt-2 text-[10px] text-gray-300 flex justify-between">
            <span>订单状态：{{ orderStatusText(voucher.orderStatus) }}</span>
            <span v-if="voucher.orderCreateTime">下单：{{ voucher.orderCreateTime }}</span>
          </div>
          <div class="mt-2 flex gap-2 justify-end">
            <template v-if="voucher.orderStatus === 1">
              <van-button size="mini" round plain type="primary" @click.stop="handleCancel(voucher)">取消订单</van-button>
              <van-button size="mini" round type="primary" @click.stop="handlePay(voucher)">去支付</van-button>
            </template>
            <van-button v-else-if="voucher.orderStatus === 2" size="mini" round plain type="warning" @click.stop="handleRefund(voucher)">申请退款</van-button>
            <van-button v-else-if="voucher.orderStatus === 4 || voucher.orderStatus === 5 || voucher.orderStatus === 6" size="mini" round plain type="default" @click.stop="handleDelete(voucher)">删除</van-button>
          </div>
        </div>
      </div>

      <!-- 加载更多 -->
      <div class="load-more">
        <van-loading v-if="loading" size="18" color="#f72d4e" />
        <span v-else-if="finished" class="text-xs text-gray-400">没有更多了</span>
        <van-button v-else size="small" round plain type="primary" @click="loadMore()">
          加载更多
        </van-button>
      </div>
    </div>

    <!-- 伪支付弹窗 -->
    <van-dialog v-model:show="payDialog.show" title="确认支付" :show-confirm-button="false">
      <div class="px-5 py-4">
        <div class="text-center text-sm text-gray-500 mb-4">
          支付金额 <span class="text-2xl font-bold text-gray-800">¥{{ formatMoney(payDialog.order?.payValue) }}</span>
        </div>
        <van-radio-group v-model="payDialog.payType" direction="vertical" class="mb-4">
          <van-radio v-for="opt in PAY_TYPE_OPTIONS" :key="opt.value" :name="opt.value" class="py-2 border rounded-lg px-3 mb-2">
            {{ opt.label }}
          </van-radio>
        </van-radio-group>
        <div class="flex gap-3">
          <van-button block round plain type="default" @click="payDialog.show = false">取消</van-button>
          <van-button block round type="primary" :loading="payDialog.paying" @click="doPay">确认支付</van-button>
        </div>
      </div>
    </van-dialog>
  </div>
</template>

<style scoped>
:deep(.van-button--primary) {
  background-color: #f72d4e;
  border-color: #f72d4e;
}

.load-more {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 0 24px;
}
</style>
