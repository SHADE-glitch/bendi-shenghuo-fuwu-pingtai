<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { showToast } from 'vant';
import { merchantShopService } from '@/api/merchantShop';
import { shopService } from '@/api/shop';
import { getErrorMessage } from '@/api/client';
import type { MerchantShopDTO, MerchantShopWriteRequest } from '@/api/types';
import { formatScore } from '@/utils/format';
import { CITY_LIST, DEFAULT_LOCATION, readCityName } from '@/utils/location';
import LoadingState from '@/components/LoadingState.vue';
import EmptyState from '@/components/EmptyState.vue';
import ErrorState from '@/components/ErrorState.vue';

/**
 * 商家店铺（列表 + 新增/编辑，Phase 4.6 Step 1 → Step 2）
 *
 * 数据来源：GET /shops/my —— 查询范围由后端按当前登录商家的 owner_id 推导，
 * 前端不传任何 owner 参数；匿名 401+10001 / 非商家 403+40003 由后端强制。
 *
 * 写链路（本 Step 新增）：
 * - 新增：POST /shops，owner_id 由后端从登录态注入，前端不传；
 * - 编辑：PUT /shops/{shopId}，归属由后端 MerchantShopGuard.assertOwner 强制。
 * 前端 `requiresMerchant` 守卫与「编辑」按钮仅做 UX 收敛，**不承担安全职责**（§22）。
 */

const MERCHANT_SHOPS_KEY = 'merchant-shops';

const router = useRouter();
const goBack = () => router.back();

/**
 * 后端 `Result.ok(data)` 不设置 total → `Result.total === null`，
 * 因此本接口**不分页**：直接取 `data`，不使用 Pagination / usePagination。
 */
const { data, isLoading, isError, refetch } = useQuery({
  queryKey: [MERCHANT_SHOPS_KEY],
  queryFn: async () => {
    const res = await merchantShopService.queryMyShops();
    return res.data.data || [];
  },
  refetchOnWindowFocus: false,
});

const shops = computed<MerchantShopDTO[]>(() => data.value ?? []);

const handleRetry = () => refetch();

/**
 * 分类名映射：复用既有 `shopService.queryTypeList()`（与 Home / Map 使用同一
 * `queryKey: ['shopTypes']`，命中同一份缓存），不新增接口、不新建 ShopType 管理模块。
 */
const { data: types } = useQuery({
  queryKey: ['shopTypes'],
  queryFn: () => shopService.queryTypeList().then((res) => res.data.data || []),
});

const typeNameById = computed(() => {
  const map = new Map<string, string>();
  for (const t of types.value ?? []) map.set(String(t.id), t.name);
  return map;
});

/** 类型表尚未加载时退回显示 ID，不阻塞店铺列表渲染 */
const typeName = (typeId: number | string) => typeNameById.value.get(String(typeId)) || `分类 ${typeId}`;

// ------------------------------------------------------------------
// 新增 / 编辑共用表单（§8 一个弹窗，§15/§16 一份显式 payload）
// ------------------------------------------------------------------

/** 字段长度上限，与 tb_shop DDL 的 varchar 宽度一致（后端 @Valid 为空操作，§13 只能前端兜） */
const MAX_LEN = { name: 128, address: 255, openHours: 32 } as const;
/** 经纬度合法区间（§12）；后端不校验，越界值会照样落库（已用真实 PUT 验证） */
const RANGE = { x: [0, 180], y: [0, 90] } as const;

interface ShopForm {
  /**
   * `null` = 新增；否则为被编辑店铺的 id。
   * 保持 DTO 原始的 `number | string`（§19）：后端 Long 序列化为 String，
   * 转 Number 会丢精度，故原样透传。
   */
  id: number | string | null;
  name: string;
  /**
   * 与 `van-radio` 的 `:name`（`String(type.id)`）**严格相等**（Vant Radio 用 `===`
   * 比较），因此用 string 承载；提交时后端可正常归一化（已用真实 PUT 验证）。
   */
  typeId: string;
  address: string;
  openHours: string;
  /** 用 string 承载输入，便于「清空后判必填」与数值校验 */
  x: string;
  y: string;
}

/**
 * 新增时的默认坐标（§11）：取「当前所选城市」的坐标；
 * `readCityName()` 默认返回「杭州」，其坐标即 `DEFAULT_LOCATION`。
 * 找不到城市时兜底 `DEFAULT_LOCATION`，不编造坐标。
 */
const defaultGeo = () => {
  const city = CITY_LIST.find((c) => c.name === readCityName());
  return city ? { x: city.x, y: city.y } : { ...DEFAULT_LOCATION };
};

const createEmptyForm = (): ShopForm => {
  const { x, y } = defaultGeo();
  return { id: null, name: '', typeId: '', address: '', openHours: '', x: String(x), y: String(y) };
};

const showForm = ref(false);
const form = ref<ShopForm>(createEmptyForm());
const isEditing = computed(() => form.value.id !== null);

/** §20 关闭/取消即复位，避免下次打开看到上一次的残留 */
const resetForm = () => {
  form.value = createEmptyForm();
};

const openCreate = () => {
  resetForm();
  showForm.value = true;
};

/** §10/§19 编辑直接用列表行数据初始化，**不**额外请求 GET /shops/{id} */
const openEdit = (shop: MerchantShopDTO) => {
  form.value = {
    id: shop.id,
    name: shop.name,
    typeId: String(shop.typeId),
    address: shop.address,
    openHours: shop.openHours ?? '',
    x: String(shop.x),
    y: String(shop.y),
  };
  showForm.value = true;
};

const closeForm = () => {
  showForm.value = false;
  resetForm();
};

/** 校验失败返回提示文案，通过返回 null（§13；不做超范围校验 §14） */
const validate = (): string | null => {
  const f = form.value;

  const name = f.name.trim();
  if (!name) return '请填写店铺名称';
  if (name.length > MAX_LEN.name) return `店铺名称最长 ${MAX_LEN.name} 个字符`;

  if (!f.typeId) return '请选择店铺分类';
  if (!typeNameById.value.has(f.typeId)) return '店铺分类无效，请重新选择';

  const address = f.address.trim();
  if (!address) return '请填写店铺地址';
  if (address.length > MAX_LEN.address) return `店铺地址最长 ${MAX_LEN.address} 个字符`;

  const openHours = f.openHours.trim();
  if (!openHours) return '请填写营业时间';
  if (openHours.length > MAX_LEN.openHours) return `营业时间最长 ${MAX_LEN.openHours} 个字符`;

  const x = Number(f.x);
  if (!f.x.trim() || Number.isNaN(x)) return '请填写正确的经度';
  if (x < RANGE.x[0] || x > RANGE.x[1]) return `经度需在 ${RANGE.x[0]} ~ ${RANGE.x[1]} 之间`;

  const y = Number(f.y);
  if (!f.y.trim() || Number.isNaN(y)) return '请填写正确的纬度';
  if (y < RANGE.y[0] || y > RANGE.y[1]) return `纬度需在 ${RANGE.y[0]} ~ ${RANGE.y[1]} 之间`;

  return null;
};

/**
 * §15/§16 新增与更新共用同一份**显式** payload：只含商家可编辑的 6 个字段。
 * - 不用 spread，杜绝把表单外字段顺手带上；
 * - 不含 `ownerId`（后端注入）、`sold`/`comments`/`score`（服务端管理字段）、
 *   `images`（由 `merchantShop.ts` 固定为 `'[]'`）。
 */
const buildPayload = (): MerchantShopWriteRequest => {
  const f = form.value;
  return {
    name: f.name.trim(),
    typeId: f.typeId,
    address: f.address.trim(),
    openHours: f.openHours.trim(),
    x: Number(f.x),
    y: Number(f.y),
  };
};

const queryClient = useQueryClient();

/**
 * 新增与编辑共用一个 mutation：按提交瞬间固化的 `id` 分流。
 * `id` 随变量传入而非在 mutationFn 里读 `form`，避免 `closeForm()` 复位后读到 `null`。
 *
 * 不使用 Idempotency-Key：与既有普通实体 CRUD（`shopComment`/`feedback`）一致，
 * 幂等头只用于订单状态流转；防重复提交由 `saveMutation.isPending` 守卫（§17）。
 */
const saveMutation = useMutation({
  // 返回类型统一为 void：createShop 返回新 id、updateShop 无返回，二者联合会让
  // mutation 的 data 类型退化成联合；而 §18 成功后一律 invalidate 重拉列表，
  // 不需要在本地消费新 id，故这里直接丢弃响应。
  mutationFn: async (vars: { id: number | string | null; payload: MerchantShopWriteRequest }) => {
    if (vars.id === null) {
      await merchantShopService.createShop(vars.payload);
    } else {
      await merchantShopService.updateShop(vars.id, vars.payload);
    }
  },
});

const saving = computed(() => saveMutation.isPending.value);

const handleSubmit = async () => {
  // §17 防重复提交：mutation 进行中直接忽略后续点击
  if (saveMutation.isPending.value) return;

  const error = validate();
  if (error) {
    showToast(error);
    return;
  }

  const id = form.value.id;
  try {
    await saveMutation.mutateAsync({ id, payload: buildPayload() });
    showToast(id === null ? '店铺创建成功' : '店铺已更新');
    closeForm();
    // §18 不手改本地列表，统一重新拉取后端真实数据
    await queryClient.invalidateQueries({ queryKey: [MERCHANT_SHOPS_KEY] });
  } catch (err) {
    // §21 复用既有错误处理；失败时**保持弹窗打开**，不丢用户输入。
    // 注：client.ts 拦截器已统一 toast，此处会再提示一次（既有全局行为，见 Step 0 反馈坑 7）
    showToast(getErrorMessage(err));
  }
};
</script>

<template>
  <div class="merchant-shops-page min-h-screen bg-gray-50">
    <van-nav-bar
      title="商家店铺"
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
      <!-- 顶部入口（§9）：加载成功后即可新增 -->
      <div class="px-3 pt-3">
        <van-button
          type="primary"
          block
          round
          icon="plus"
          data-testid="shop-create-top"
          @click="openCreate"
        >
          新增店铺
        </van-button>
      </div>

      <!-- Empty -->
      <EmptyState
        v-if="shops.length === 0"
        title="暂无店铺"
        description="你还没有创建任何店铺"
      >
        <!-- §9 空态内入口：复用 EmptyState 默认插槽，不修改该组件 -->
        <van-button
          size="small"
          round
          type="primary"
          icon="plus"
          class="mt-4"
          data-testid="shop-create-empty"
          @click="openCreate"
        >
          新增店铺
        </van-button>
      </EmptyState>

      <!-- Shop List -->
      <div v-else class="p-3">
        <div class="mb-2 text-xs text-gray-400">共 {{ shops.length }} 个店铺</div>

        <div
          v-for="shop in shops"
          :key="shop.id"
          data-testid="merchant-shop-card"
          class="mb-3 rounded-xl border border-gray-100 bg-white p-4 shadow-sm"
        >
          <div class="flex items-start justify-between gap-2">
            <h3 class="font-bold text-gray-800">{{ shop.name }}</h3>
            <span
              data-testid="shop-type"
              class="flex-shrink-0 rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500"
            >
              {{ typeName(shop.typeId) }}
            </span>
          </div>

          <p class="mt-1 text-xs text-gray-500">{{ shop.address }}</p>

          <div class="mt-2 flex items-center justify-between text-xs text-gray-400">
            <span>营业时间：{{ shop.openHours || '未设置' }}</span>
            <span class="text-sm font-semibold text-gray-700">
              {{ formatScore(shop.score).toFixed(1) }} 分
            </span>
          </div>

          <div class="mt-2 flex items-center gap-4 text-[11px] text-gray-400">
            <span>月售 {{ shop.sold }}</span>
            <span>评价 {{ shop.comments }}</span>
          </div>

          <div class="mt-2 break-all font-mono text-[10px] text-gray-300">店铺号：{{ shop.id }}</div>

          <!-- §10 编辑入口：直接用该行列表数据，不再请求详情 -->
          <div class="mt-3 flex justify-end">
            <van-button
              size="small"
              round
              plain
              type="primary"
              data-testid="shop-edit"
              @click="openEdit(shop)"
            >
              编辑
            </van-button>
          </div>
        </div>
      </div>
    </template>

    <!-- 新增 / 编辑共用弹窗（§8） -->
    <van-popup
      v-model:show="showForm"
      position="bottom"
      round
      :close-on-click-overlay="false"
      :style="{ maxHeight: '88%', paddingBottom: '16px' }"
    >
      <div class="p-5 pb-1">
        <h3 class="mb-3 text-center text-base font-bold text-gray-800">
          {{ isEditing ? '编辑店铺' : '新增店铺' }}
        </h3>

        <van-cell-group inset>
          <van-field
            v-model="form.name"
            label="店铺名称"
            placeholder="请输入店铺名称"
            maxlength="128"
            input-align="right"
            data-testid="field-name"
          />

          <van-field label="店铺分类" data-testid="field-typeId">
            <template #input>
              <van-radio-group v-model="form.typeId" direction="horizontal" class="flex-wrap">
                <van-radio
                  v-for="t in types"
                  :key="t.id"
                  :name="String(t.id)"
                  class="mr-3"
                  data-testid="type-option"
                >
                  {{ t.name }}
                </van-radio>
              </van-radio-group>
            </template>
          </van-field>

          <van-field
            v-model="form.address"
            label="店铺地址"
            placeholder="请输入详细地址"
            maxlength="255"
            input-align="right"
            data-testid="field-address"
          />

          <van-field
            v-model="form.openHours"
            label="营业时间"
            placeholder="如 10:00-22:00"
            maxlength="32"
            input-align="right"
            data-testid="field-openHours"
          />

          <van-field
            v-model="form.x"
            type="number"
            label="经度 x"
            placeholder="0 ~ 180"
            input-align="right"
            data-testid="field-x"
          />

          <van-field
            v-model="form.y"
            type="number"
            label="纬度 y"
            placeholder="0 ~ 90"
            input-align="right"
            data-testid="field-y"
          />
        </van-cell-group>

        <div class="mt-4 flex gap-3">
          <van-button round plain block :disabled="saving" data-testid="shop-cancel" @click="closeForm">
            取消
          </van-button>
          <van-button
            round
            type="primary"
            block
            :loading="saving"
            data-testid="shop-save"
            @click="handleSubmit"
          >
            {{ isEditing ? '保存' : '创建' }}
          </van-button>
        </div>
      </div>
    </van-popup>
  </div>
</template>
