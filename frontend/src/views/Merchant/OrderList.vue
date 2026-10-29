<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { showConfirmDialog, showToast } from 'vant';
import { ErrorCode, OrderStatus, type MerchantVoucherOrderItemDTO } from '@/api/types';
import { merchantOrderService } from '@/api/merchantOrder';
import { formatDateTime, formatMoney, orderStatusText } from '@/utils/format';
import Pagination from '@/components/ui/Pagination.vue';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';

const router = useRouter();
const goBack = () => router.back();

/**
 * 前端 ErrorCode 枚举未收录商家归属错误码（后端 MerchantAccessException 专用 40004），
 * 此处仅按码值判断；不改动 api/types.ts，避免扩大本阶段范围。
 */
const SHOP_NOT_OWNED = '40004';

/** 这些错误码表示「本地列表已过期」，处理完必须重新拉取后端真实状态 */
const STALE_LIST_ERRORS = new Set<string>([
  ErrorCode.ORDER_STATUS_INVALID, // 30009 已被核销 / 非 PAID / 并发竞争失败
  ErrorCode.ORDER_NOT_FOUND, // 30008 订单已不存在
  SHOP_NOT_OWNED, // 40004 订单归属已变化
]);

const MERCHANT_ORDERS_KEY = 'merchant-orders';

/**
 * 状态筛选（商家视角措辞）：
 * - 全部   → 不传 status（后端按缺省处理）
 * - 待核销 → status=2（已支付，等待商家核销）
 * - 已核销 → status=3
 */
const STATUS_TABS: ReadonlyArray<{ label: string; status?: OrderStatus }> = [
  { label: '全部' },
  { label: '待核销', status: OrderStatus.PAID },
  { label: '已核销', status: OrderStatus.USED },
];

const PAGE_SIZE = 10;

/**
 * URL query → 初始 tab（Phase 4.9 Step 1，Dashboard 下钻用）。
 * 仅初始化一次：无 query / 未知值 → 0「全部」（与既有默认行为完全一致）；
 * `tab=pending` → 1「待核销」(status=2)；`tab=completed` → 2「已核销」(status=3)。
 * 手动切换 tab 不写回 URL（不做 URL-driven 全量改造，刻意保持最小）。
 */
const TAB_QUERY_TO_INDEX: Record<string, number> = { pending: 1, completed: 2 };
const route = useRoute();
const resolveInitialTab = (): number => {
  const q = route.query.tab;
  const key = Array.isArray(q) ? q[0] : q;
  return typeof key === 'string' ? (TAB_QUERY_TO_INDEX[key] ?? 0) : 0;
};

const activeTab = ref(resolveInitialTab());
const current = ref(1);

const status = computed(() => STATUS_TABS[activeTab.value]?.status);

// 切换筛选条件必须回到第 1 页，否则会在新条件下停留在旧页码而出现空页
watch(activeTab, () => {
  current.value = 1;
});

/**
 * 商家订单查询（只读）：
 * - queryKey 区分 current / size / status，任一变化都会重新请求；
 * - 不使用 placeholderData（keepPreviousData），确保切换 status 时不会把旧筛选的数据
 *   当成新筛选的结果展示；
 * - total 来自后端 Result.total（Long→String），统一用 Number() 安全转换。
 */
const { data, isLoading, isError, refetch } = useQuery({
  queryKey: [
    MERCHANT_ORDERS_KEY,
    computed(() => ({ current: current.value, size: PAGE_SIZE, status: status.value ?? null })),
  ],
  queryFn: async () => {
    const res = await merchantOrderService.queryMerchantOrders(current.value, PAGE_SIZE, status.value);
    return {
      list: res.data.data || [],
      total: Number(res.data.total || 0),
    };
  },
  refetchOnWindowFocus: false,
});

const orders = computed<MerchantVoucherOrderItemDTO[]>(() => data.value?.list ?? []);
const total = computed(() => data.value?.total ?? 0);
const showPagination = computed(() => total.value > PAGE_SIZE);

const handleRetry = () => refetch();

// ------------------------------------------------------------------
// 核销（Redeem）：PAID(2) -> USED(3)
// 复用 Phase 4.2 的 merchantOrderService.redeemMerchantOrder()（已带 Idempotency-Key）；
// 前端只负责「确认 + 防重复 + 正确响应」，最终一致性由后端 CAS 保证。
// ------------------------------------------------------------------
const queryClient = useQueryClient();

const redeemMutation = useMutation({
  mutationFn: (orderId: number | string) => merchantOrderService.redeemMerchantOrder(orderId),
});

/** 正在核销的订单 id（仅用于按钮 loading，避免整页 LoadingState） */
const redeemingId = ref<number | string | null>(null);

/** 核销成功后不手动改 status / useTime，统一重新拉取后端真实数据 */
const refreshList = () => queryClient.invalidateQueries({ queryKey: [MERCHANT_ORDERS_KEY] });

const handleRedeem = async (order: MerchantVoucherOrderItemDTO) => {
  // 只有「已支付（待核销）」的订单可核销；UI 已按此显隐，这里再兜一层
  if (order.status !== OrderStatus.PAID) return;
  // 防重复提交：mutation 进行中直接忽略后续点击
  if (redeemMutation.isPending.value) return;

  const confirmed = await showConfirmDialog({
    title: '确认核销',
    message: '确定要核销这张优惠券吗？',
    theme: 'round-button',
    confirmButtonText: '确认',
    cancelButtonText: '再想想',
  }).then(
    () => true,
    () => false
  );
  if (!confirmed) return;

  redeemingId.value = order.orderId;
  try {
    await redeemMutation.mutateAsync(order.orderId);
    showToast('核销成功');
    await refreshList();
  } catch (e) {
    const code = (e as { errorCode?: string | null })?.errorCode ?? '';
    if (code === ErrorCode.ORDER_STATUS_INVALID) {
      showToast('订单状态已变化，请刷新后重试');
    } else if (code === SHOP_NOT_OWNED) {
      showToast('该订单不属于你的店铺');
    } else {
      // 40003 / 网络错误等沿用 client.ts 既有错误体系，这里只做兜底提示
      showToast((e as { errorMsg?: string | null })?.errorMsg || '核销失败，请重试');
    }
    // 列表已过期的错误：恢复到后端真实状态，避免停留在不可恢复的旧数据上
    if (STALE_LIST_ERRORS.has(code)) await refreshList();
  } finally {
    redeemingId.value = null;
  }
};
</script>

<template>
  <div class="merchant-orders-page min-h-screen bg-gray-50">
    <van-nav-bar
      title="商家订单"
      left-arrow
      safe-area-inset-top
      fixed
      placeholder
      @click-left="goBack"
    />

    <van-tabs v-model:active="activeTab" color="#f72d4e">
      <van-tab v-for="tab in STATUS_TABS" :key="tab.label" :title="tab.label" />
    </van-tabs>

    <!-- Loading -->
    <LoadingState v-if="isLoading" />

    <!-- Error -->
    <ErrorState v-else-if="isError" @retry="handleRetry" />

    <!-- Empty -->
    <EmptyState v-else-if="orders.length === 0" title="暂无订单" />

    <!-- Order List -->
    <div v-else class="p-3">
      <div class="mb-2 text-xs text-gray-400">共 {{ total }} 条</div>

      <div
        v-for="order in orders"
        :key="order.orderId"
        data-testid="merchant-order-card"
        class="mb-3 rounded-xl border border-gray-100 bg-white p-4 shadow-sm"
      >
        <div class="flex items-start justify-between gap-2">
          <h3 class="font-bold text-gray-800">{{ order.voucherTitle || '优惠券' }}</h3>
          <span data-testid="order-status" class="flex-shrink-0 text-xs text-gray-500">{{ orderStatusText(order.status) }}</span>
        </div>

        <p v-if="order.shopName" class="mt-1 text-xs text-gray-500">{{ order.shopName }}</p>

        <div class="mt-2 flex items-center justify-between text-xs text-gray-400">
          <span>买家：{{ order.userId }}</span>
          <span class="text-sm font-semibold text-gray-700">¥{{ formatMoney(order.payValue) }}</span>
        </div>

        <div class="mt-2 break-all font-mono text-[10px] text-gray-300">订单号：{{ order.orderId }}</div>

        <div class="mt-2 space-y-0.5 text-[11px] text-gray-400">
          <div>创建：{{ formatDateTime(order.createTime) }}</div>
          <div>支付：{{ formatDateTime(order.payTime) }}</div>
          <div>核销：{{ formatDateTime(order.useTime) }}</div>
        </div>

        <!-- 仅「已支付（待核销）」订单可核销 -->
        <div v-if="order.status === OrderStatus.PAID" class="mt-3 flex justify-end">
          <van-button
            size="small"
            round
            type="primary"
            data-testid="redeem-button"
            :loading="redeemingId === order.orderId"
            :disabled="redeemingId !== null"
            @click="handleRedeem(order)"
          >
            核销
          </van-button>
        </div>
      </div>

      <Pagination
        v-if="showPagination"
        v-model:current="current"
        :total="total"
        :page-size="PAGE_SIZE"
        class="py-4"
      />
    </div>
  </div>
</template>
