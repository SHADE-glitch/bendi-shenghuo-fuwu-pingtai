<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useQuery } from '@tanstack/vue-query';
import { merchantShopService } from '@/api/merchantShop';
import { merchantVoucherService } from '@/api/merchantVoucher';
import { merchantOrderService } from '@/api/merchantOrder';
import { OrderStatus, type MerchantVoucherOrderItemDTO } from '@/api/types';
import { formatDateTime, formatMoney, orderStatusText } from '@/utils/format';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';

/**
 * 商家经营概览（Phase 4.8 Step 1）
 *
 * 纯前端、零后端：所有指标都来自既有 API 的真实数据：
 * - GET /shops/my               → 我的店铺数
 * - GET /vouchers/my            → 优惠券总数 / 上架 / 秒杀（完整数组，前端统计）
 * - GET /voucher-orders/merchant→ 最近订单；status=2/3 各查一次 total → 待核销 / 已核销
 *
 * 刻意不做的：金额聚合 / 时间窗口 / 实时秒杀库存 / 图表 —— 数据不支持或会误导，
 * 详见 docs/phase4/phase4-8-step0-merchant-dashboard-recon-report.md。
 *
 * Query Key 复用既有 Merchant 页面缓存（同一个 key 前缀），不另造 Dashboard 专属数据源。
 * 订单统计一律用后端 Result.total，绝不用「当前页数组长度」代表全量。
 */

const MERCHANT_ORDERS_KEY = 'merchant-orders';

const router = useRouter();
const goBack = () => router.back();

/**
 * 下钻导航（Phase 4.9 Step 1）：统计卡与最近列表直达已有明细页。
 * 目标页均已有 Merchant route guard + 后端权限，无需再次做 role 判断；
 * OrderList 的 tab 预选走 `?tab=`（pending→待核销 status=2，completed→已核销 status=3），
 * 无 query 时 OrderList 保持默认「全部」（行为不变）。
 */
const goToShops = () => router.push('/merchant/shops');
const goToVouchers = () => router.push('/merchant/vouchers');
const goToPendingOrders = () => router.push({ path: '/merchant/orders', query: { tab: 'pending' } });
const goToCompletedOrders = () =>
  router.push({ path: '/merchant/orders', query: { tab: 'completed' } });
const goToOrders = () => router.push('/merchant/orders');

/** 我的店铺（缓存与 ShopList 共享） */
const { data: shops, isLoading: shopsLoading, isError: shopsError, refetch: shopsRetry } = useQuery({
  queryKey: ['merchant-shops'],
  queryFn: () => merchantShopService.queryMyShops().then((res) => res.data.data || []),
  refetchOnWindowFocus: false,
});

/** 我的优惠券（完整数组：总额 / 类型 / 状态全部由前端从数组计算） */
const { data: vouchers, isLoading: vouchersLoading, isError: vouchersError, refetch: vouchersRetry } = useQuery({
  queryKey: ['merchant-vouchers'],
  queryFn: () => merchantVoucherService.queryMyVouchers().then((res) => res.data.data || []),
  refetchOnWindowFocus: false,
});

/** 最近订单（最新一页）—— size=5 */
const { data: recentOrdersData, isError: recentOrdersError, refetch: recentOrdersRetry } = useQuery({
  queryKey: [MERCHANT_ORDERS_KEY, { current: 1, size: 5, status: null }],
  queryFn: async () => {
    const res = await merchantOrderService.queryMerchantOrders(1, 5, null);
    return { list: res.data.data || [], total: Number(res.data.total || 0) };
  },
  refetchOnWindowFocus: false,
});

/** 待核销订单数 = status=2 的 total（size=1 足够，只需总数） */
const { data: pendingData, isError: pendingError } = useQuery({
  queryKey: [MERCHANT_ORDERS_KEY, { current: 1, size: 1, status: OrderStatus.PAID }],
  queryFn: async () => {
    const res = await merchantOrderService.queryMerchantOrders(1, 1, OrderStatus.PAID);
    return Number(res.data.total || 0);
  },
  refetchOnWindowFocus: false,
});

/** 已核销订单数 = status=3 的 total */
const { data: redeemedData, isError: redeemedError } = useQuery({
  queryKey: [MERCHANT_ORDERS_KEY, { current: 1, size: 1, status: OrderStatus.USED }],
  queryFn: async () => {
    const res = await merchantOrderService.queryMerchantOrders(1, 1, OrderStatus.USED);
    return Number(res.data.total || 0);
  },
  refetchOnWindowFocus: false,
});

// ---------------- 统计口径（前端从数组 / 后端 total） ----------------
const shopCount = computed(() => shops.value?.length ?? 0);

const voucherTotal = computed(() => vouchers.value?.length ?? 0);
const voucherPublished = computed(() => vouchers.value?.filter((v) => v.status === 1).length ?? 0);
const voucherSeckill = computed(() => vouchers.value?.filter((v) => v.type === 1).length ?? 0);

const pendingCount = computed(() => (pendingError.value ? null : (pendingData.value ?? 0)));
const redeemedCount = computed(() => (redeemedError.value ? null : (redeemedData.value ?? 0)));

const recentOrders = computed<MerchantVoucherOrderItemDTO[]>(() => recentOrdersData.value?.list ?? []);
const recentVouchers = computed(() => (vouchers.value ?? []).slice(0, 5));

// ---------------- 页面状态 ----------------
const pageLoading = computed(() => shopsLoading.value || vouchersLoading.value);
const pageError = computed(() => shopsError.value || vouchersError.value);
const retryAll = () => {
  shopsRetry();
  vouchersRetry();
};

const voucherStatusText = { 1: '上架', 2: '下架', 3: '过期' } as const;
</script>

<template>
  <div class="merchant-dashboard-page min-h-screen bg-gray-50">
    <van-nav-bar
      title="经营概览"
      left-arrow
      safe-area-inset-top
      fixed
      placeholder
      @click-left="goBack"
    />

    <LoadingState v-if="pageLoading" />
    <ErrorState v-else-if="pageError" @retry="retryAll" />

    <template v-else>
      <!-- 统计卡片（2 列网格，点击下钻到明细页） -->
      <div class="grid grid-cols-2 gap-3 p-3">
        <div
          data-testid="stat-shops"
          class="cursor-pointer rounded-xl bg-white p-4 shadow-sm transition active:bg-gray-100"
          @click="goToShops"
        >
          <div class="text-xs text-gray-400">我的店铺</div>
          <div data-testid="stat-shops-value" class="mt-1 text-2xl font-bold text-gray-800">
            {{ shopCount }}
          </div>
        </div>

        <div
          data-testid="stat-vouchers"
          class="cursor-pointer rounded-xl bg-white p-4 shadow-sm transition active:bg-gray-100"
          @click="goToVouchers"
        >
          <div class="text-xs text-gray-400">优惠券</div>
          <div data-testid="stat-vouchers-value" class="mt-1 text-2xl font-bold text-gray-800">
            {{ voucherTotal }}
          </div>
          <div class="mt-1 text-[11px] text-gray-400">
            上架 {{ voucherPublished }} · 秒杀 {{ voucherSeckill }}
          </div>
        </div>

        <div
          data-testid="stat-pending"
          class="cursor-pointer rounded-xl bg-white p-4 shadow-sm transition active:bg-gray-100"
          @click="goToPendingOrders"
        >
          <div class="text-xs text-gray-400">待核销订单</div>
          <div data-testid="stat-pending-value" class="mt-1 text-2xl font-bold text-orange-500">
            {{ pendingError ? '—' : pendingCount }}
          </div>
        </div>

        <div
          data-testid="stat-redeemed"
          class="cursor-pointer rounded-xl bg-white p-4 shadow-sm transition active:bg-gray-100"
          @click="goToCompletedOrders"
        >
          <div class="text-xs text-gray-400">已核销订单</div>
          <div data-testid="stat-redeemed-value" class="mt-1 text-2xl font-bold text-green-600">
            {{ redeemedError ? '—' : redeemedCount }}
          </div>
        </div>
      </div>

      <!-- 最近订单 -->
      <section class="px-3 pb-2">
        <h3 class="mb-2 text-sm font-bold text-gray-800">最近订单</h3>

        <div v-if="recentOrdersError" class="rounded-xl bg-white p-4 text-center text-xs text-gray-400">
          订单数据加载失败
          <van-button size="mini" round plain class="mt-2" @click="recentOrdersRetry">重试</van-button>
        </div>

        <EmptyState v-else-if="recentOrders.length === 0" title="暂无订单" />

        <div v-else class="space-y-2">
          <div
            v-for="order in recentOrders"
            :key="order.orderId"
            data-testid="recent-order"
            class="cursor-pointer rounded-xl bg-white p-3 text-sm shadow-sm transition active:bg-gray-100"
            @click="goToOrders"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="truncate font-medium text-gray-800">{{ order.voucherTitle || '—' }}</span>
              <span class="flex-shrink-0 rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">
                {{ orderStatusText(order.status) }}
              </span>
            </div>
            <div class="mt-1 flex items-center gap-3 text-xs text-gray-400">
              <span>¥{{ formatMoney(Number(order.payValue ?? 0)) }}</span>
              <span v-if="order.createTime">{{ formatDateTime(order.createTime) }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 最近优惠券 -->
      <section class="px-3 pb-4">
        <h3 class="mb-2 text-sm font-bold text-gray-800">最近优惠券</h3>

        <EmptyState v-if="recentVouchers.length === 0" title="暂无优惠券" />

        <div v-else class="space-y-2">
          <div
            v-for="voucher in recentVouchers"
            :key="voucher.id"
            data-testid="recent-voucher"
            class="cursor-pointer rounded-xl bg-white p-3 text-sm shadow-sm transition active:bg-gray-100"
            @click="goToVouchers"
          >
            <div class="flex items-center justify-between gap-2">
              <span class="truncate font-medium text-gray-800">{{ voucher.title }}</span>
              <span class="flex flex-shrink-0 gap-1 text-xs">
                <span class="rounded-full bg-gray-100 px-2 py-0.5 text-gray-500">
                  {{ voucher.type === 1 ? '秒杀券' : '普通券' }}
                </span>
                <span class="rounded-full bg-gray-100 px-2 py-0.5 text-gray-500">
                  {{ voucherStatusText[voucher.status as 1 | 2 | 3] ?? `状态 ${voucher.status}` }}
                </span>
              </span>
            </div>
            <div v-if="voucher.type === 1" class="mt-1 text-xs text-gray-400">
              库存 {{ voucher.stock ?? '-' }}
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>