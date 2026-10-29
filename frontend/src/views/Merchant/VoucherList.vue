<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { showToast } from 'vant';
import { merchantVoucherService } from '@/api/merchantVoucher';
import { merchantShopService } from '@/api/merchantShop';
import { getErrorMessage } from '@/api/client';
import type {
  MerchantVoucherCreateRequest,
  MerchantVoucherDTO,
  MerchantVoucherUpdateRequest,
} from '@/api/types';
import { formatDateTime, formatMoney } from '@/utils/format';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';

/**
 * 商家优惠券（列表 + 新增/编辑 + 上架/下架，Phase 4.7 Step 1）
 *
 * 数据来源：GET /vouchers/my —— 查询范围由后端按当前登录商家的 owner_id 推导，
 * 前端不传 owner；匿名 401+10001 / 非商家 403+40003 / 非本店 40004 由后端强制。
 *
 * 列表不分页（后端 total=null）。店铺名来自复用的 `['merchant-shops']` 缓存
 * （GET /shops/my），不新增 Shop 接口。
 *
 * 写链路：
 * - 新增普通券：POST /vouchers（type=0）
 * - 新增秒杀券：POST /vouchers/seckill-vouchers（type=1，后端不校验 type，必须显式发送）
 * - 编辑 / 上架下架：PUT /vouchers/{id}（后端白名单断言归属）
 */

const MERCHANT_VOUCHERS_KEY = 'merchant-vouchers';
const MERCHANT_SHOPS_KEY = 'merchant-shops';

const router = useRouter();
const goBack = () => router.back();

// ---------------- 查询：我的优惠券 ----------------
const { data, isLoading, isError, refetch } = useQuery({
  queryKey: [MERCHANT_VOUCHERS_KEY],
  queryFn: async () => {
    const res = await merchantVoucherService.queryMyVouchers();
    return res.data.data || [];
  },
  refetchOnWindowFocus: false,
});

const vouchers = computed<MerchantVoucherDTO[]>(() => data.value ?? []);
const handleRetry = () => refetch();

// ---------------- 店铺映射：复用 ['merchant-shops'] 缓存 ----------------
const { data: shops } = useQuery({
  queryKey: [MERCHANT_SHOPS_KEY],
  queryFn: () => merchantShopService.queryMyShops().then((res) => res.data.data || []),
});

const shopNameById = computed(() => {
  const map = new Map<string, string>();
  for (const s of shops.value ?? []) map.set(String(s.id), s.name);
  return map;
});
const shopName = (shopId: number | string) =>
  shopNameById.value.get(String(shopId)) || `店铺 ${shopId}`;
const hasShops = computed(() => (shops.value?.length ?? 0) > 0);

// ---------------- 状态展示 / 操作（1 上架 / 2 下架 / 3 过期；只允许 1↔2） ----------------
const statusOption = { 1: '上架', 2: '下架', 3: '过期' } as const;
const statusText = (status: number): string => statusOption[status as keyof typeof statusOption] ?? `状态 ${status}`;

// ---------------- 金额工具（元 ↔ 分；分→元仅本地展示用） ----------------
/** 元输入 → 分；非法/负值/空 → null（不做 Number()/parseInt 的精度破坏） */
const yuanToFen = (yuan: string): number | null => {
  const t = (yuan ?? '').trim();
  if (!t) return null;
  const n = Number(t);
  if (!Number.isFinite(n) || Number.isNaN(n) || n < 0) return null;
  return Math.round(n * 100);
};
/** 分 → 元输入（回填编辑表单；非整数给两位小数） */
const fenToYuanInput = (fen: number | string | null): string => {
  const v = Number(fen ?? 0) / 100;
  return Number.isInteger(v) ? String(v) : v.toFixed(2);
};

// ---------------- 秒杀时间工具（空格格式 ←→ ISO T 格式） ----------------
const DATETIME_RE = /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}/;
const toISO = (s: string): string => s.trim().replace(' ', 'T');
const beginEndValid = (begin: string, end: string): boolean => {
  const b = Date.parse(toISO(begin));
  const e = Date.parse(toISO(end));
  return Number.isFinite(b) && Number.isFinite(e) && b < e;
};

// ---------------- 表单 ----------------
interface VoucherForm {
  /** null=新增；否则为被编辑券 id */
  id: number | string | null;
  /** create-only：shopId（Vant radio `:name` 为 String，用字符串承载，提交时后端可归一化） */
  shopId: string;
  /** create-only：0 普通券 / 1 秒杀券 */
  type: 0 | 1;
  title: string;
  subTitle: string;
  rules: string;
  /** 输入单位为「元」，提交时 ×100 转分 */
  payYuan: string;
  actualYuan: string;
  /** create 秒杀券才用到 */
  stock: string;
  beginTime: string; // 'YYYY-MM-DD HH:mm:ss'（空格格式，提交转 T）
  endTime: string;
  /** edit-only：1 上架 / 2 下架（3 过期只读展示，不提供操作） */
  status: number;
}

const createEmptyForm = (): VoucherForm => ({
  id: null,
  shopId: '',
  type: 0,
  title: '',
  subTitle: '',
  rules: '',
  payYuan: '',
  actualYuan: '',
  stock: '',
  beginTime: '',
  endTime: '',
  status: 1,
});

const showForm = ref(false);
const form = ref<VoucherForm>(createEmptyForm());
const isEditing = computed(() => form.value.id !== null);

const resetForm = () => {
  form.value = createEmptyForm();
};

const openCreate = () => {
  if (!hasShops.value) {
    showToast('请先创建店铺');
    return;
  }
  resetForm();
  showForm.value = true;
};

/** 编辑直接用列表行数据回填（金额分→元），不额外请求 voucher detail，后端无对应接口 */
const openEdit = (v: MerchantVoucherDTO) => {
  form.value = {
    id: v.id,
    shopId: '',
    type: v.type,
    title: v.title,
    subTitle: v.subTitle ?? '',
    rules: v.rules ?? '',
    payYuan: fenToYuanInput(v.payValue),
    actualYuan: fenToYuanInput(v.actualValue),
    stock: '',
    beginTime: '',
    endTime: '',
    status: v.status,
  };
  showForm.value = true;
};

const closeForm = () => {
  showForm.value = false;
  resetForm();
};

const MAX_LEN = { title: 255, subTitle: 255, rules: 1024 } as const;

const validate = (): string | null => {
  const f = form.value;

  if (!isEditing.value) {
    if (!f.shopId) return '请选择店铺';
  }

  const title = f.title.trim();
  if (!title) return '请填写优惠券标题';
  if (title.length > MAX_LEN.title) return `标题最长 ${MAX_LEN.title} 个字符`;

  const pay = yuanToFen(f.payYuan);
  if (pay === null) return '请填写正确的支付金额（元）';
  const actual = yuanToFen(f.actualYuan);
  if (actual === null) return '请填写正确的抵扣金额（元）';
  if (actual < pay) return '抵扣金额不能小于支付金额';

  if (!isEditing.value && f.type === 1) {
    const stock = Number(f.stock);
    if (!f.stock.trim() || !Number.isInteger(stock) || stock <= 0) return '库存需为正整数';
    if (!DATETIME_RE.test(f.beginTime)) return '请填写正确的开始时间（YYYY-MM-DD HH:mm）';
    if (!DATETIME_RE.test(f.endTime)) return '请填写正确的结束时间（YYYY-MM-DD HH:mm）';
    if (!beginEndValid(f.beginTime, f.endTime)) return '开始时间必须早于结束时间';
  }

  return null;
};

// ---------------- Payload：按 create normal / create seckill / edit 显式构造 ----------------
const buildCreatePayload = (): MerchantVoucherCreateRequest => {
  const f = form.value;
  const base = {
    shopId: f.shopId,
    title: f.title.trim(),
    subTitle: f.subTitle.trim() || null,
    rules: f.rules.trim() || null,
    payValue: yuanToFen(f.payYuan)!,
    actualValue: yuanToFen(f.actualYuan)!,
    type: f.type,
  };
  if (f.type === 1) {
    return {
      ...base,
      type: 1,
      stock: Number(f.stock),
      beginTime: toISO(f.beginTime),
      endTime: toISO(f.endTime),
    } as MerchantVoucherCreateRequest;
  }
  return base;
};

const buildUpdatePayload = (): MerchantVoucherUpdateRequest => {
  const f = form.value;
  return {
    title: f.title.trim(),
    subTitle: f.subTitle.trim() || null,
    rules: f.rules.trim() || null,
    payValue: yuanToFen(f.payYuan)!,
    actualValue: yuanToFen(f.actualYuan)!,
    status: f.status,
  };
};

// ---------------- Mutations ----------------
const queryClient = useQueryClient();

/** 新增/编辑共用一个 mutation：按提交瞬间固化的 mode 分流（同 ShopList 方案） */
const saveMutation = useMutation({
  mutationFn: async (vars: {
    mode: 'create' | 'edit';
    id: number | string | null;
    createPayload?: MerchantVoucherCreateRequest;
    updatePayload?: MerchantVoucherUpdateRequest;
  }) => {
    if (vars.mode === 'create') {
      const p = vars.createPayload!;
      if (p.type === 1) await merchantVoucherService.createSeckillVoucher(p);
      else await merchantVoucherService.createVoucher(p);
    } else {
      await merchantVoucherService.updateVoucher(vars.id!, vars.updatePayload!);
    }
  },
});

const saving = computed(() => saveMutation.isPending.value);

const handleSubmit = async () => {
  if (saveMutation.isPending.value) return;
  const error = validate();
  if (error) {
    showToast(error);
    return;
  }

  const id = form.value.id;
  const editing = isEditing.value;
  try {
    await saveMutation.mutateAsync({
      mode: editing ? 'edit' : 'create',
      id,
      createPayload: editing ? undefined : buildCreatePayload(),
      updatePayload: editing ? buildUpdatePayload() : undefined,
    });
    showToast(editing ? '优惠券已更新' : '优惠券创建成功');
    closeForm();
    await queryClient.invalidateQueries({ queryKey: [MERCHANT_VOUCHERS_KEY] });
  } catch (err) {
    showToast(getErrorMessage(err));
  }
};

/** 卡片上架/下架：只携带 { title, status }，status-only（title 后端 @NotBlank） */
const statusMutation = useMutation({
  mutationFn: async (vars: { id: number | string; title: string; status: number }) =>
    merchantVoucherService.updateVoucher(vars.id, {
      title: vars.title,
      status: vars.status,
    }),
});

const handleToggleStatus = async (v: MerchantVoucherDTO) => {
  if (statusMutation.isPending.value) return;
  const next = v.status === 1 ? 2 : 1;
  try {
    await statusMutation.mutateAsync({ id: v.id, title: v.title, status: next });
    showToast(next === 2 ? '已下架' : '已上架');
    await queryClient.invalidateQueries({ queryKey: [MERCHANT_VOUCHERS_KEY] });
  } catch (err) {
    showToast(getErrorMessage(err));
  }
};
</script>

<template>
  <div class="merchant-vouchers-page min-h-screen bg-gray-50">
    <van-nav-bar
      title="商家优惠券"
      left-arrow
      safe-area-inset-top
      fixed
      placeholder
      @click-left="goBack"
    />

    <!-- Loading -->
    <LoadingState v-if="isLoading" />

    <!-- Error -->
    <ErrorState v-else-if="isError" @retry="handleRetry" />

    <template v-else>
      <!-- 顶部入口：无店铺必须先行建店再新增（后端要求券必须绑定 shop） -->
      <div class="px-3 pt-3">
        <van-button
          type="primary"
          block
          round
          icon="plus"
          data-testid="voucher-create"
          :disabled="!hasShops"
          @click="openCreate"
        >
          新增优惠券
        </van-button>
        <p v-if="!hasShops" class="mt-2 text-center text-xs text-gray-400" data-testid="no-shops-hint">
          请先创建店铺后再新增优惠券
        </p>
      </div>

      <!-- Empty -->
      <EmptyState v-if="vouchers.length === 0" title="暂无优惠券" description="你还没有创建任何优惠券">
        <van-button
          size="small"
          round
          type="primary"
          icon="plus"
          class="mt-4"
          data-testid="voucher-create-empty"
          :disabled="!hasShops"
          @click="openCreate"
        >
          新增优惠券
        </van-button>
      </EmptyState>

      <!-- Voucher List -->
      <div v-else class="p-3">
        <div class="mb-2 text-xs text-gray-400">共 {{ vouchers.length }} 张优惠券</div>

        <div
          v-for="voucher in vouchers"
          :key="voucher.id"
          data-testid="voucher-card"
          class="mb-3 rounded-xl border border-gray-100 bg-white p-4 shadow-sm"
        >
          <div class="flex items-start justify-between gap-2">
            <h3 class="font-bold text-gray-800">{{ voucher.title }}</h3>
            <div class="flex flex-shrink-0 gap-1">
              <span
                data-testid="voucher-type"
                class="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500"
              >
                {{ voucher.type === 1 ? '秒杀券' : '普通券' }}
              </span>
              <span
                data-testid="voucher-status"
                class="rounded-full px-2 py-0.5 text-xs"
                :class="
                  voucher.status === 1
                    ? 'bg-green-50 text-green-600'
                    : voucher.status === 2
                      ? 'bg-orange-50 text-orange-600'
                      : 'bg-red-50 text-red-500'
                "
              >
                {{ statusText(voucher.status) }}
              </span>
            </div>
          </div>

          <p class="mt-1 text-xs text-gray-500">
            店铺：{{ shopName(voucher.shopId) }}
          </p>

          <p v-if="voucher.subTitle" class="mt-1 text-xs text-gray-400">{{ voucher.subTitle }}</p>
          <p v-if="voucher.rules" class="mt-1 text-sm text-gray-700 leading-relaxed whitespace-pre-line">
            {{ voucher.rules }}
          </p>

          <div class="mt-2 flex items-center gap-4 text-sm">
            <span class="font-semibold text-red-500">¥{{ formatMoney(Number(voucher.actualValue ?? 0)) }}</span>
            <span class="text-xs text-gray-400">满 ¥{{ formatMoney(Number(voucher.payValue ?? 0)) }} 可用</span>
          </div>

          <!-- 秒杀信息 -->
          <div
            v-if="voucher.type === 1"
            data-testid="voucher-seckill-info"
            class="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-gray-400"
          >
            <span>库存 {{ voucher.stock ?? '-' }}</span>
            <span v-if="voucher.beginTime || voucher.endTime">
              {{ formatDateTime(voucher.beginTime) }} ~ {{ formatDateTime(voucher.endTime) }}
            </span>
          </div>

          <!-- 操作：编辑 + 上架/下架（status=3 只展示不提供操作） -->
          <div class="mt-3 flex justify-end gap-2">
            <van-button
              size="small"
              round
              plain
              type="primary"
              data-testid="voucher-edit"
              @click="openEdit(voucher)"
            >
              编辑
            </van-button>
            <van-button
              v-if="voucher.status === 1 || voucher.status === 2"
              size="small"
              round
              :type="voucher.status === 1 ? 'warning' : 'success'"
              :loading="statusMutation.isPending.value"
              data-testid="voucher-status-toggle"
              @click="handleToggleStatus(voucher)"
            >
              {{ voucher.status === 1 ? '下架' : '上架' }}
            </van-button>
          </div>
        </div>
      </div>
    </template>

    <!-- 新增 / 编辑共用弹窗 -->
    <van-popup
      v-model:show="showForm"
      position="bottom"
      round
      :close-on-click-overlay="false"
      :style="{ maxHeight: '88%', paddingBottom: '16px' }"
    >
      <div class="p-5 pb-1">
        <h3 class="mb-3 text-center text-base font-bold text-gray-800">
          {{ isEditing ? '编辑优惠券' : '新增优惠券' }}
        </h3>

        <van-cell-group inset>
          <!-- 创建：店铺 + 券类型 -->
          <template v-if="!isEditing">
            <van-field label="所属店铺">
              <template #input>
                <van-radio-group v-model="form.shopId" direction="vertical" class="w-full">
                  <van-radio
                    v-for="shop in shops"
                    :key="shop.id"
                    :name="String(shop.id)"
                    class="mb-2"
                    data-testid="shop-option"
                  >
                    {{ shop.name }}
                  </van-radio>
                </van-radio-group>
              </template>
            </van-field>

            <van-field label="券类型">
              <template #input>
                <van-radio-group v-model="form.type" direction="horizontal" class="flex-wrap">
                  <van-radio :name="0" class="mr-3" data-testid="type-option">普通券</van-radio>
                  <van-radio :name="1" data-testid="type-option">秒杀券</van-radio>
                </van-radio-group>
              </template>
            </van-field>
          </template>

          <van-field
            v-model="form.title"
            label="标题"
            placeholder="请输入优惠券标题"
            :maxlength="MAX_LEN.title"
            input-align="right"
            data-testid="field-title"
          />
          <van-field
            v-model="form.subTitle"
            label="副标题"
            placeholder="选填"
            :maxlength="MAX_LEN.subTitle"
            input-align="right"
            data-testid="field-subTitle"
          />
          <van-field
            v-model="form.rules"
            label="使用规则"
            placeholder="选填"
            type="textarea"
            rows="2"
            autosize
            :maxlength="MAX_LEN.rules"
            data-testid="field-rules"
          />
          <van-field
            v-model="form.payYuan"
            label="支付金额(元)"
            type="number"
            placeholder="如 47.50"
            input-align="right"
            data-testid="field-payYuan"
          />
          <van-field
            v-model="form.actualYuan"
            label="抵扣金额(元)"
            type="number"
            placeholder="如 50.00"
            input-align="right"
            data-testid="field-actualYuan"
          />

          <!-- 编辑：状态（1 上架 / 2 下架；3 只读展示） -->
          <template v-if="isEditing">
            <van-field v-if="form.status === 1 || form.status === 2" label="状态">
              <template #input>
                <van-radio-group v-model="form.status" direction="horizontal" class="flex-wrap">
                  <van-radio :name="1" class="mr-3" data-testid="status-option">上架</van-radio>
                  <van-radio :name="2" data-testid="status-option">下架</van-radio>
                </van-radio-group>
              </template>
            </van-field>
            <van-field v-else label="状态" readonly :model-value="statusText(form.status)" input-align="right" />
          </template>

          <!-- 创建秒杀券：额外字段 -->
          <template v-if="!isEditing && form.type === 1">
            <van-field
              v-model="form.stock"
              label="库存"
              type="number"
              placeholder="正整数"
              input-align="right"
              data-testid="field-stock"
            />
            <van-field
              v-model="form.beginTime"
              label="开始时间"
              placeholder="YYYY-MM-DD HH:mm"
              input-align="right"
              data-testid="field-beginTime"
            />
            <van-field
              v-model="form.endTime"
              label="结束时间"
              placeholder="YYYY-MM-DD HH:mm"
              input-align="right"
              data-testid="field-endTime"
            />
          </template>
        </van-cell-group>

        <div class="mt-4 flex gap-3">
          <van-button round plain block :disabled="saving" data-testid="voucher-cancel" @click="closeForm">
            取消
          </van-button>
          <van-button
            round
            type="primary"
            block
            :loading="saving"
            data-testid="voucher-save"
            @click="handleSubmit"
          >
            {{ isEditing ? '保存' : '创建' }}
          </van-button>
        </div>
      </div>
    </van-popup>
  </div>
</template>