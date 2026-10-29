import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { showConfirmDialog, showToast } from 'vant';
import type { MerchantVoucherOrderItemDTO, OrderStatus } from '@/api/types';
import { merchantOrderService } from '@/api/merchantOrder';
import MerchantOrderList from '@/views/Merchant/OrderList.vue';

// 页面只用到 router.back()，无需真实路由；useRoute 的 query 由各用例经 routeState 预置
const routeState = vi.hoisted(() => ({ query: {} as Record<string, unknown> }));
vi.mock('vue-router', () => ({
  useRouter: () => ({ back: vi.fn(), push: vi.fn() }),
  useRoute: () => ({ query: routeState.query }),
}));

// 只替换命令式弹窗/提示，保留真实 Vant 组件（van-button / van-tabs 等）供模板使用
vi.mock('vant', async () => {
  const actual = await vi.importActual<typeof import('vant')>('vant');
  return {
    ...actual,
    showConfirmDialog: vi.fn(),
    showToast: vi.fn(),
  };
});

// 只 mock 商家订单 API，验证「UI 是否正确消费 API 契约」（后端权限链/CAS 由 Phase 3.4-B E2E 覆盖）
vi.mock('@/api/merchantOrder', () => ({
  merchantOrderService: {
    queryMerchantOrders: vi.fn(),
    redeemMerchantOrder: vi.fn(),
  },
}));

const queryMerchantOrders = vi.mocked(merchantOrderService.queryMerchantOrders);
const redeemMerchantOrder = vi.mocked(merchantOrderService.redeemMerchantOrder);
const confirmDialog = vi.mocked(showConfirmDialog);
const toast = vi.mocked(showToast);

const okResult = (list: MerchantVoucherOrderItemDTO[], total: number | string) => ({
  data: {
    success: true,
    errorCode: null,
    errorMsg: null,
    data: list,
    total,
    traceId: null,
  },
});

const okRedeem = { data: { success: true, errorCode: null, errorMsg: null, data: null, total: null, traceId: null } };

const makeItem = (
  overrides: Partial<MerchantVoucherOrderItemDTO> = {}
): MerchantVoucherOrderItemDTO => ({
  orderId: '9000000000000000001',
  status: 2 as OrderStatus,
  createTime: '2026-09-21T10:00:00',
  payTime: '2026-09-21T10:05:00',
  useTime: null,
  userId: '1001',
  voucherId: '5001',
  voucherTitle: '双人套餐券',
  payValue: 12345,
  actualValue: 20000,
  shopId: '3001',
  shopName: '老王烧烤',
  ...overrides,
});

const mountPage = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return mount(MerchantOrderList, {
    global: { plugins: [[VueQueryPlugin, { queryClient }]] },
  });
};

type Wrapper = ReturnType<typeof mountPage>;

const cards = (wrapper: Wrapper) => wrapper.findAll('[data-testid="merchant-order-card"]');
const redeemButtons = (wrapper: Wrapper) => wrapper.findAll('[data-testid="redeem-button"]');
const statusText = (wrapper: Wrapper, index = 0) =>
  cards(wrapper)[index].find('[data-testid="order-status"]').text();

/** 点击第 n 张卡片的「核销」按钮并等待确认流程 */
const clickRedeem = async (wrapper: Wrapper, index = 0) => {
  await redeemButtons(wrapper)[index].trigger('click');
  await flushPromises();
};

beforeEach(() => {
  vi.clearAllMocks();
  // 默认无 query（与既有行为一致：选中「全部」）
  routeState.query = {};
  // 默认：确认框点「确认」、核销成功
  confirmDialog.mockResolvedValue(undefined as never);
  redeemMerchantOrder.mockResolvedValue(okRedeem as never);
});

describe('Merchant/OrderList.vue — 查询（Phase 4.3 基线）', () => {
  it('渲染订单卡片：券名 / 店铺 / 买家 / 订单号，且首次请求 status 缺省', async () => {
    queryMerchantOrders.mockResolvedValue(okResult([makeItem()], 1) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(queryMerchantOrders).toHaveBeenCalledWith(1, 10, undefined);

    const text = wrapper.text();
    expect(text).toContain('双人套餐券');
    expect(text).toContain('老王烧烤');
    expect(text).toContain('买家：1001');
    expect(text).toContain('9000000000000000001');
  });

  it('状态徽标使用既有 orderStatusText()（2→已支付，3→已核销）', async () => {
    queryMerchantOrders.mockResolvedValue(
      okResult(
        [
          makeItem({ orderId: 'a', status: 2 as OrderStatus }),
          makeItem({ orderId: 'b', status: 3 as OrderStatus, useTime: '2026-09-21T11:00:00' }),
        ],
        2
      ) as never
    );
    const wrapper = mountPage();
    await flushPromises();

    expect(statusText(wrapper, 0)).toBe('已支付');
    expect(statusText(wrapper, 1)).toBe('已核销');
  });

  it('金额使用 formatMoney()（分 → 元）', async () => {
    queryMerchantOrders.mockResolvedValue(
      okResult([makeItem({ orderId: 'm', payValue: 12345 })], 1) as never
    );
    const wrapper = mountPage();
    await flushPromises();

    expect(cards(wrapper)[0].text()).toContain('¥123.45');
  });

  it('空时间统一显示 -（formatDateTime）', async () => {
    queryMerchantOrders.mockResolvedValue(
      okResult([makeItem({ createTime: null, payTime: null, useTime: null })], 1) as never
    );
    const wrapper = mountPage();
    await flushPromises();

    const card = cards(wrapper)[0];
    expect(card.text()).toContain('创建：-');
    expect(card.text()).toContain('支付：-');
    expect(card.text()).toContain('核销：-');
  });

  it('空数据显示 EmptyState', async () => {
    queryMerchantOrders.mockResolvedValue(okResult([], 0) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('暂无订单');
    expect(cards(wrapper)).toHaveLength(0);
  });

  it('加载中显示 LoadingState', async () => {
    queryMerchantOrders.mockReturnValue(new Promise(() => {}) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('加载中');
    expect(cards(wrapper)).toHaveLength(0);
  });

  it('请求失败显示 ErrorState，点击「重试」重新发起请求', async () => {
    queryMerchantOrders.mockRejectedValueOnce(new Error('boom'));
    queryMerchantOrders.mockResolvedValueOnce(okResult([makeItem()], 1) as never);

    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('加载失败');

    const retry = wrapper.findAll('button').find((btn) => btn.text().includes('重试'));
    await retry!.trigger('click');
    await flushPromises();

    expect(queryMerchantOrders).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain('双人套餐券');
  });

  it('分页：total 取后端 Result.total（String 安全转换）', async () => {
    const page = Array.from({ length: 10 }, (_, i) => makeItem({ orderId: `o${i}` }));
    queryMerchantOrders.mockResolvedValue(okResult(page, '25') as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('共 25 条');
    expect(wrapper.findAll('button').some((b) => b.text().includes('下一页'))).toBe(true);
  });

  it('切换状态筛选：current 回到 1，且携带对应 status', async () => {
    const page = Array.from({ length: 10 }, (_, i) => makeItem({ orderId: `o${i}` }));
    queryMerchantOrders.mockResolvedValue(okResult(page, '25') as never);
    const wrapper = mountPage();
    await flushPromises();

    const next = wrapper.findAll('button').find((b) => b.text().includes('下一页'));
    await next!.trigger('click');
    await flushPromises();
    expect(queryMerchantOrders).toHaveBeenLastCalledWith(2, 10, undefined);

    const tabs = wrapper.findAll('.van-tab');
    await tabs[1].trigger('click');
    await flushPromises();
    expect(queryMerchantOrders).toHaveBeenLastCalledWith(1, 10, 2);

    await tabs[2].trigger('click');
    await flushPromises();
    expect(queryMerchantOrders).toHaveBeenLastCalledWith(1, 10, 3);
  });
});

describe('Merchant/OrderList.vue — 核销入口显隐', () => {
  it('仅 status=2（已支付）显示核销按钮；status=3 不显示', async () => {
    queryMerchantOrders.mockResolvedValue(
      okResult(
        [
          makeItem({ orderId: 'a', status: 2 as OrderStatus }),
          makeItem({ orderId: 'b', status: 3 as OrderStatus, useTime: '2026-09-21T11:00:00' }),
        ],
        2
      ) as never
    );
    const wrapper = mountPage();
    await flushPromises();

    expect(redeemButtons(wrapper)).toHaveLength(1);
    expect(cards(wrapper)[0].find('[data-testid="redeem-button"]').exists()).toBe(true);
    expect(cards(wrapper)[1].find('[data-testid="redeem-button"]').exists()).toBe(false);
  });

  it('status ∈ {1,3,4,5,6} 均不显示核销按钮，也不产生核销请求', async () => {
    const statuses: OrderStatus[] = [1, 3, 4, 5, 6] as OrderStatus[];
    queryMerchantOrders.mockResolvedValue(
      okResult(
        statuses.map((s, i) => makeItem({ orderId: `s${i}`, status: s })),
        statuses.length
      ) as never
    );
    const wrapper = mountPage();
    await flushPromises();

    expect(redeemButtons(wrapper)).toHaveLength(0);
    expect(redeemMerchantOrder).not.toHaveBeenCalled();
  });
});

describe('Merchant/OrderList.vue — 确认交互', () => {
  it('确认框点「取消」不调用核销 API，订单状态不变', async () => {
    queryMerchantOrders.mockResolvedValue(okResult([makeItem({ status: 2 as OrderStatus })], 1) as never);
    confirmDialog.mockRejectedValue(new Error('cancel'));

    const wrapper = mountPage();
    await flushPromises();

    await clickRedeem(wrapper);

    expect(confirmDialog).toHaveBeenCalledTimes(1);
    expect(redeemMerchantOrder).not.toHaveBeenCalled();
    expect(statusText(wrapper)).toBe('已支付');
  });

  it('确认后以原始 orderId（Long 字符串，不转 Number）调用核销', async () => {
    const orderId = '9000000000000000001';
    queryMerchantOrders.mockResolvedValue(okResult([makeItem({ orderId })], 1) as never);

    const wrapper = mountPage();
    await flushPromises();

    await clickRedeem(wrapper);

    expect(confirmDialog).toHaveBeenCalledWith(
      expect.objectContaining({ title: '确认核销', message: '确定要核销这张优惠券吗？' })
    );
    expect(redeemMerchantOrder).toHaveBeenCalledWith(orderId);
    expect(typeof redeemMerchantOrder.mock.calls[0][0]).toBe('string');
  });
});

describe('Merchant/OrderList.vue — 核销成功后的刷新', () => {
  it('核销成功后提示并重新 GET 列表（invalidate/refetch）', async () => {
    queryMerchantOrders.mockResolvedValue(okResult([makeItem()], 1) as never);

    const wrapper = mountPage();
    await flushPromises();
    expect(queryMerchantOrders).toHaveBeenCalledTimes(1);

    await clickRedeem(wrapper);
    await flushPromises();

    expect(redeemMerchantOrder).toHaveBeenCalledTimes(1);
    expect(queryMerchantOrders).toHaveBeenCalledTimes(2);
    expect(toast).toHaveBeenCalledWith('核销成功');
  });

  it('「全部」Tab 核销成功后展示后端最新状态（已核销 + useTime），不手动改本地状态', async () => {
    let redeemed = false;
    queryMerchantOrders.mockImplementation(
      async () =>
        (redeemed
          ? okResult([makeItem({ status: 3 as OrderStatus, useTime: '2026-09-21T11:30:00' })], 1)
          : okResult([makeItem({ status: 2 as OrderStatus })], 1)) as never
    );
    redeemMerchantOrder.mockImplementation(async () => {
      redeemed = true;
      return okRedeem as never;
    });

    const wrapper = mountPage();
    await flushPromises();
    expect(statusText(wrapper)).toBe('已支付');

    await clickRedeem(wrapper);
    await flushPromises();

    expect(statusText(wrapper)).toBe('已核销');
    expect(cards(wrapper)[0].text()).toContain('核销：2026-09-21 11:30');
    // 已核销订单不再显示核销按钮
    expect(redeemButtons(wrapper)).toHaveLength(0);
  });

  it('「待核销」Tab 核销成功后该订单从列表消失（EmptyState）', async () => {
    let redeemed = false;
    queryMerchantOrders.mockImplementation(
      async (_current: number, _size: number, st?: OrderStatus | number | null) => {
        if (st !== 2) return okResult([], 0) as never;
        return (redeemed
          ? okResult([], 0)
          : okResult([makeItem({ status: 2 as OrderStatus })], 1)) as never;
      }
    );
    redeemMerchantOrder.mockImplementation(async () => {
      redeemed = true;
      return okRedeem as never;
    });

    const wrapper = mountPage();
    await flushPromises();

    // 切到「待核销」
    await wrapper.findAll('.van-tab')[1].trigger('click');
    await flushPromises();
    expect(cards(wrapper)).toHaveLength(1);

    await clickRedeem(wrapper);
    await flushPromises();

    expect(cards(wrapper)).toHaveLength(0);
    expect(wrapper.text()).toContain('暂无订单');
    // 重新查询仍在 status=2 条件下
    expect(queryMerchantOrders).toHaveBeenLastCalledWith(1, 10, 2);
  });
});

describe('Merchant/OrderList.vue — 错误处理与防重复', () => {
  it('核销返回 30009 时提示「订单状态已变化」并重新拉取后端真实状态', async () => {
    let reloaded = false;
    queryMerchantOrders.mockImplementation(
      async () =>
        (reloaded
          ? okResult([makeItem({ status: 3 as OrderStatus, useTime: '2026-09-21T12:00:00' })], 1)
          : okResult([makeItem({ status: 2 as OrderStatus })], 1)) as never
    );
    redeemMerchantOrder.mockImplementation(async () => {
      reloaded = true;
      throw { success: false, errorCode: '30009', errorMsg: '当前订单状态不允许该操作' };
    });

    const wrapper = mountPage();
    await flushPromises();
    expect(statusText(wrapper)).toBe('已支付');

    await clickRedeem(wrapper);
    await flushPromises();

    expect(toast).toHaveBeenCalledWith('订单状态已变化，请刷新后重试');
    // 页面可恢复：重新拉取并展示后端真实状态
    expect(queryMerchantOrders).toHaveBeenCalledTimes(2);
    expect(statusText(wrapper)).toBe('已核销');
    expect(cards(wrapper)).toHaveLength(1);
  });

  it('核销返回 40004 时提示并重新拉取列表', async () => {
    let reloaded = false;
    queryMerchantOrders.mockImplementation(
      async () =>
        (reloaded ? okResult([], 0) : okResult([makeItem({ status: 2 as OrderStatus })], 1)) as never
    );
    redeemMerchantOrder.mockImplementation(async () => {
      reloaded = true;
      throw { success: false, errorCode: '40004', errorMsg: '无权操作该店铺' };
    });

    const wrapper = mountPage();
    await flushPromises();

    await clickRedeem(wrapper);
    await flushPromises();

    expect(toast).toHaveBeenCalledWith('该订单不属于你的店铺');
    expect(queryMerchantOrders).toHaveBeenCalledTimes(2);
    expect(wrapper.text()).toContain('暂无订单');
  });

  it('mutation pending 期间连续点击只发送一次核销请求，且页面不被替换成 LoadingState', async () => {
    queryMerchantOrders.mockResolvedValue(okResult([makeItem({ status: 2 as OrderStatus })], 1) as never);
    // 永不 resolve，保持在 pending
    redeemMerchantOrder.mockReturnValue(new Promise(() => {}) as never);

    const wrapper = mountPage();
    await flushPromises();

    await clickRedeem(wrapper);
    // pending 期间再次点击
    await wrapper.find('[data-testid="redeem-button"]').trigger('click');
    await flushPromises();

    expect(redeemMerchantOrder).toHaveBeenCalledTimes(1);
    expect(confirmDialog).toHaveBeenCalledTimes(1);
    // 列表仍在（未被 LoadingState 替换）
    expect(cards(wrapper)).toHaveLength(1);
    expect(wrapper.text()).toContain('双人套餐券');
  });
});

describe('Merchant/OrderList.vue — URL query 初始化 tab（Phase 4.9 Step 1）', () => {
  const activeIndex = (wrapper: ReturnType<typeof mountPage>) =>
    wrapper.findAll('.van-tab').findIndex((t) => t.classes().includes('van-tab--active'));

  it('无 query 默认选中「全部」，首次请求 status 缺省', async () => {
    routeState.query = {};
    queryMerchantOrders.mockResolvedValue(okResult([makeItem()], 1) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(activeIndex(wrapper)).toBe(0);
    expect(queryMerchantOrders).toHaveBeenCalledWith(1, 10, undefined);
  });

  it('?tab=pending 默认选中「待核销」并使用 status=2', async () => {
    routeState.query = { tab: 'pending' };
    queryMerchantOrders.mockResolvedValue(okResult([makeItem()], 1) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(activeIndex(wrapper)).toBe(1);
    expect(queryMerchantOrders).toHaveBeenCalledWith(1, 10, 2);
  });

  it('?tab=completed 默认选中「已核销」并使用 status=3', async () => {
    routeState.query = { tab: 'completed' };
    queryMerchantOrders.mockResolvedValue(okResult([makeItem()], 1) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(activeIndex(wrapper)).toBe(2);
    expect(queryMerchantOrders).toHaveBeenCalledWith(1, 10, 3);
  });

  it('未知 tab 值回退到「全部」，不破坏手动切换', async () => {
    routeState.query = { tab: 'nope' };
    queryMerchantOrders.mockResolvedValue(okResult([makeItem()], 1) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(activeIndex(wrapper)).toBe(0);

    // query 只决定初始 tab：手动切到「待核销」后仍按 status=2 查询
    await wrapper.findAll('.van-tab')[1].trigger('click');
    await flushPromises();
    expect(queryMerchantOrders).toHaveBeenLastCalledWith(1, 10, 2);
    expect(activeIndex(wrapper)).toBe(1);
  });
});
