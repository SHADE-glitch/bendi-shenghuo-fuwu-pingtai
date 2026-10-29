import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { showToast } from 'vant';
import type { MerchantVoucherOrderItemDTO, MerchantVoucherDTO } from '@/api/types';
import { merchantShopService } from '@/api/merchantShop';
import { merchantVoucherService } from '@/api/merchantVoucher';
import { merchantOrderService } from '@/api/merchantOrder';
import MerchantDashboard from '@/views/Merchant/Dashboard.vue';

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));
vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router');
  return { ...actual, useRouter: () => ({ back: vi.fn(), push: pushMock }) };
});

// 只 mock 三个 Merchant API，验证 Dashboard 是否正确消费 API 契约
vi.mock('@/api/merchantShop', () => ({
  merchantShopService: { queryMyShops: vi.fn() },
}));
vi.mock('@/api/merchantVoucher', () => ({
  merchantVoucherService: { queryMyVouchers: vi.fn() },
}));
vi.mock('@/api/merchantOrder', () => ({
  merchantOrderService: { queryMerchantOrders: vi.fn() },
}));

vi.mock('vant', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vant')>();
  return { ...actual, showToast: vi.fn() };
});

const queryMyShops = vi.mocked(merchantShopService.queryMyShops);
const queryMyVouchers = vi.mocked(merchantVoucherService.queryMyVouchers);
const queryMerchantOrders = vi.mocked(merchantOrderService.queryMerchantOrders);
const showToastMock = vi.mocked(showToast);

/** 兼容 axios Response：`res.data` 为 Result，`total` 可带真实值 */
const ok = <T>(data: T, total: number | null = null) => ({
  data: { success: true, errorCode: null, errorMsg: null, data, total, traceId: null },
});

const SHOPS = [
  { id: '9000000000000000001', name: 'A店', typeId: '1', images: '[]', area: null, address: 'a', x: 120.1, y: 30.2, avgPrice: '80', sold: 10, comments: 2, score: 45, openHours: '10:00-22:00', city: '杭州' },
  { id: '9000000000000000002', name: 'B店', typeId: '2', images: '[]', area: null, address: 'b', x: 120.2, y: 30.3, avgPrice: '90', sold: 5, comments: 1, score: 40, openHours: '09:00-21:00', city: '杭州' },
];

const makeVoucher = (overrides: Partial<MerchantVoucherDTO> = {}): MerchantVoucherDTO => ({
  id: '9000000000000000101',
  shopId: '9000000000000000001',
  title: '券A',
  subTitle: null,
  rules: null,
  payValue: '4750',
  actualValue: '5000',
  type: 0,
  status: 1,
  stock: null,
  beginTime: null,
  endTime: null,
  createTime: '2026-09-21T10:00:00',
  updateTime: '2026-09-21T10:00:00',
  ...overrides,
});

const makeOrder = (overrides: Partial<MerchantVoucherOrderItemDTO> = {}): MerchantVoucherOrderItemDTO => ({
  orderId: '9000000000000000201',
  status: 2,
  createTime: '2026-09-21T09:00:00',
  payTime: '2026-09-21T09:05:00',
  useTime: null,
  userId: '1001',
  voucherId: '9000000000000000101',
  voucherTitle: '券A',
  payValue: '4750',
  actualValue: '5000',
  shopId: '9000000000000000001',
  shopName: 'A店',
  ...overrides,
});

/** 默认正常数据：2 店铺 / 3 券（2 上架含 1 秒杀）/ 订单 status=2→total 37 / status=3→total 12 / 最近订单 5 条 */
const mockNormal = () => {
  queryMyShops.mockResolvedValue(ok(SHOPS) as never);
  queryMyVouchers.mockResolvedValue(
    ok([
      makeVoucher({ title: '满50减2' }), // type0 status1 → 上架，非秒杀
      makeVoucher({ id: '9000000000000000102', title: '秒杀5折', type: 1, stock: 100 }), // type1 status1
      makeVoucher({ id: '9000000000000000103', title: '下架券', status: 2 }), // type0 status2
    ]) as never
  );
  queryMerchantOrders.mockImplementation(async (_c, _s, status) => {
    if (status === 2) return ok([], 37) as never; // 待核销：返回 0 行但 total=37
    if (status === 3) return ok([], 12) as never; // 已核销
    return ok([makeOrder(), makeOrder({ orderId: '2', title: undefined as never }), makeOrder({ orderId: '3' }), makeOrder({ orderId: '4' }), makeOrder({ orderId: '5' })], 50) as never;
  });
};

const mountPage = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return mount(MerchantDashboard, {
    global: { plugins: [[VueQueryPlugin, { queryClient }]] },
  });
};

type Wrapper = ReturnType<typeof mountPage>;

const valueOf = (wrapper: Wrapper, testid: string) =>
  wrapper.get(`[data-testid="${testid}"]`).text();

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  mockNormal();
});

describe('Merchant/Dashboard.vue — 正常数据（复用既有 API）', () => {
  it('店铺数来自 /shops/my 数组长度', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(valueOf(wrapper, 'stat-shops-value')).toBe('2');
  });

  it('优惠券统计：总数 = 数组长度；上架 = status===1；秒杀 = type===1', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(valueOf(wrapper, 'stat-vouchers-value')).toBe('3');
    expect(wrapper.get('[data-testid="stat-vouchers"]').text()).toContain('上架 2');
    expect(wrapper.get('[data-testid="stat-vouchers"]').text()).toContain('秒杀 1');
  });

  it('待核销 / 已核销订单数来自后端 total（不是分页数组长度）', async () => {
    // 关键测试：status=2 查询返回 0 行但 total=37，页面必须显示 37 而非 0
    const wrapper = mountPage();
    await flushPromises();
    expect(valueOf(wrapper, 'stat-pending-value')).toBe('37');
    expect(valueOf(wrapper, 'stat-redeemed-value')).toBe('12');
    // 未误用「当前页长度」：该页只有 0 行
    expect(queryMerchantOrders).toHaveBeenCalledWith(1, 1, 2);
    expect(queryMerchantOrders).toHaveBeenCalledWith(1, 1, 3);
  });

  it('最近订单：复用 GET /voucher-orders/merchant 首屏', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(queryMerchantOrders).toHaveBeenCalledWith(1, 5, null);
    expect(wrapper.findAll('[data-testid="recent-order"]')).toHaveLength(5);
  });

  it('最近优惠券：复用 GET /vouchers/my，取数组前 5', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.findAll('[data-testid="recent-voucher"]')).toHaveLength(3);
  });
});

describe('Merchant/Dashboard.vue — 空状态', () => {
  it('无店铺 → 店铺数 0；无优惠券 → 优惠券 0 且显示「暂无优惠券」；无订单 → 「暂无订单」', async () => {
    queryMyShops.mockResolvedValue(ok([]) as never);
    queryMyVouchers.mockResolvedValue(ok([]) as never);
    queryMerchantOrders.mockImplementation(async (_c, _s) => ok([], 0) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(valueOf(wrapper, 'stat-shops-value')).toBe('0');
    expect(valueOf(wrapper, 'stat-vouchers-value')).toBe('0');
    expect(wrapper.text()).toContain('暂无优惠券');
    expect(wrapper.text()).toContain('暂无订单');
  });
});

describe('Merchant/Dashboard.vue — 异常状态', () => {
  it('关键 API（/vouchers/my）失败 → 显示 ErrorState，点击重试后恢复', async () => {
    queryMyVouchers.mockRejectedValueOnce(new Error('boom'));
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.text()).toContain('加载失败');

    await wrapper.get('button').trigger('click');
    await flushPromises();
    expect(valueOf(wrapper, 'stat-vouchers-value')).toBe('3');
  });
});

describe('Merchant/Dashboard.vue — 路由与权限', () => {
  it('/merchant/dashboard 已注册，meta 与既有 merchant 页一致（requiresAuth + requiresMerchant）', async () => {
    const { default: router } = await import('@/router');
    const r = router.resolve('/merchant/dashboard');
    expect(r.name).toBe('MerchantDashboard');
    expect(r.meta.requiresAuth).toBe(true);
    expect(r.meta.requiresMerchant).toBe(true);
  });

  it('消费展示用 Vue Query，不新增任何自定义状态管理', () => {
    expect(showToastMock).not.toHaveBeenCalled();
  });
});

describe('Merchant/Dashboard.vue — 下钻导航（Phase 4.9 Step 1）', () => {
  it.each([
    ['stat-shops', '/merchant/shops'],
    ['stat-vouchers', '/merchant/vouchers'],
  ])('统计卡 %s 点击进入 %s', async (testid, path) => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.get(`[data-testid="${testid}"]`).trigger('click');
    expect(pushMock).toHaveBeenCalledWith(path);
  });

  it.each([
    ['stat-pending', 'pending'],
    ['stat-redeemed', 'completed'],
  ])('订单统计卡 %s 点击进入 /merchant/orders?tab=%s', async (testid, tab) => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.get(`[data-testid="${testid}"]`).trigger('click');
    expect(pushMock).toHaveBeenCalledWith({ path: '/merchant/orders', query: { tab } });
  });

  it('最近订单点击进入 /merchant/orders（无 Merchant Order Detail，不新增详情页）', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.findAll('[data-testid="recent-order"]')[0].trigger('click');
    expect(pushMock).toHaveBeenCalledWith('/merchant/orders');
  });

  it('最近优惠券点击进入 /merchant/vouchers（不新增详情页）', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await wrapper.findAll('[data-testid="recent-voucher"]')[0].trigger('click');
    expect(pushMock).toHaveBeenCalledWith('/merchant/vouchers');
  });
});