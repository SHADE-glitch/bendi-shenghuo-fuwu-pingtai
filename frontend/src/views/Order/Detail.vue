<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';
import { orderService } from '@/api/orderService';
import { formatDateTime, firstImage, formatMoney, orderStatusText } from '@/utils/format';
import { PAY_TYPE_OPTIONS, useVoucherOrderActions } from '@/composables/useVoucherOrderActions';

const route = useRoute();
const router = useRouter();
const orderId = route.params.orderId as string;

const { data: detail, isLoading, isError, refetch, isFetching } = useQuery({
  queryKey: ['orderDetail', orderId],
  queryFn: async () => {
    const res = await orderService.queryOrderDetail(orderId);
    return res.data.data;
  },
  refetchOnWindowFocus: false,
});

const { payDialog, openPayDialog, doPay, cancelOrder, refundOrder, deleteOrder } = useVoucherOrderActions(refetch);

const statusText = computed(() => {
  // 详情为空且请求已失败：订单已被物理删除或链接失效，不能笼统显示「未知状态」
  if (!detail.value && isError.value) return '订单不存在或已删除';
  return orderStatusText(detail.value?.status);
});

/** 删除是物理删除，成功后本页数据源已不存在，必须离开详情页 */
const onDelete = async () => {
  if (!detail.value) return;
  if (await deleteOrder(detail.value)) router.replace('/my-vouchers');
};

const goHome = () => router.replace('/');
const goShop = () => {
  const shopId = detail.value?.shopId;
  if (shopId) router.push(`/shop/${shopId}`);
};
</script>

<template>
  <div class="order-detail-page min-h-screen bg-gray-50 p-4">
    <van-nav-bar title="订单详情" left-arrow fixed placeholder @click-left="router.back()" />

    <div class="bg-white rounded-xl p-5 mt-2 shadow-sm">
      <div class="text-xs text-gray-400">订单号</div>
      <div class="mt-1 text-base font-bold break-all">{{ orderId }}</div>

      <div class="mt-5 text-xs text-gray-400">订单状态</div>
      <div v-if="isLoading" class="mt-2 text-sm text-gray-500">
        <van-loading size="16" /> 查询中...
      </div>
      <div v-else class="mt-2 text-base font-semibold text-[#e84855]">{{ statusText }}</div>
    </div>

    <div v-if="detail" class="bg-white rounded-xl p-5 mt-3 shadow-sm">
      <div class="text-xs text-gray-400">优惠券信息</div>
      <div
        v-if="detail.shopName"
        class="mt-2 flex items-center gap-2 cursor-pointer"
        @click="goShop"
      >
        <van-image v-if="firstImage(detail.shopImage)" round fit="cover" :src="firstImage(detail.shopImage)" class="w-6 h-6 flex-shrink-0" />
        <span class="text-sm font-medium text-gray-600 truncate">{{ detail.shopName }}</span>
        <van-icon name="arrow" size="12" class="text-gray-300 flex-shrink-0" />
      </div>
      <div class="mt-2 text-base font-semibold text-gray-800">{{ detail.voucherTitle || '优惠券' }}</div>
      <div class="mt-1 text-xs text-gray-400">{{ detail.voucherRules || '全场通用' }}</div>
      <div class="mt-2 flex items-center gap-1 text-xs text-gray-400">
        <van-icon name="clock-o" />
        <span v-if="detail.beginTime && detail.endTime">有效期：{{ formatDateTime(detail.beginTime) }} ~ {{ formatDateTime(detail.endTime) }}</span>
        <span v-else>长期有效</span>
      </div>

      <div class="mt-4 grid grid-cols-2 gap-3 text-sm text-gray-600">
        <div>
          <div class="text-xs text-gray-400">支付金额</div>
          <div class="mt-1">¥{{ formatMoney(detail.payValue) }}</div>
        </div>
        <div>
          <div class="text-xs text-gray-400">抵扣金额</div>
          <div class="mt-1">¥{{ formatMoney(detail.actualValue) }}</div>
        </div>
      </div>

      <div class="mt-4 text-xs text-gray-500 space-y-1">
        <div>下单时间：{{ detail.createTime || '-' }}</div>
        <div>支付时间：{{ detail.payTime || '-' }}</div>
        <div>核销时间：{{ detail.useTime || '-' }}</div>
        <div>退款时间：{{ detail.refundTime || '-' }}</div>
      </div>
    </div>

    <div class="mt-4 grid grid-cols-2 gap-3">
      <van-button block round plain type="primary" :loading="isFetching" @click="refetch">
        刷新状态
      </van-button>
      <van-button block round type="primary" @click="goHome">
        返回首页
      </van-button>
    </div>

    <div v-if="detail && !isLoading" class="mt-4">
      <div v-if="detail.status === 1" class="grid grid-cols-2 gap-3">
        <van-button block round plain type="default" @click="cancelOrder(detail)">
          取消订单
        </van-button>
        <van-button block round type="primary" @click="openPayDialog(detail)">
          去支付
        </van-button>
      </div>
      <van-button v-else-if="detail.status === 2" block round plain type="warning" @click="refundOrder(detail)">
        申请退款
      </van-button>
      <van-button v-else-if="detail.status === 4 || detail.status === 5 || detail.status === 6" block round plain type="default" @click="onDelete">
        删除订单
      </van-button>
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
