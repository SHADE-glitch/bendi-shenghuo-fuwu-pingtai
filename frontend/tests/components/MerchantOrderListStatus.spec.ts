import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import type { MerchantVoucherOrderItemDTO, OrderStatus } from '@/api/types';
import { merchantOrderService } from '@/api/merchantOrder';
import MerchantOrderList from '@/views/Merchant/OrderList.vue';

/**
 * P2 缺口补充：商家订单视图对长尾状态（4 / 5 / 6）的断言此前偏弱
 * —— 既有 `MerchantOrderList.spec.ts` 只断言了 status=2/3 的文案，
 * 4/5/6 仅验证「不显示核销按钮」。
 *
 * 本文件补齐「状态文案」这一维度的观测（页面消费 API 契约的 UI 表现）：
 *   - status=4 → 已取消
 *   - status=6 → 已退款
 *   - status=5 见下方 it.todo：后端 `OrderStatus.EXPIRED=5` 与前端「退款中」
 *     存在已知语义漂移（PROJECT_STATUS_REPORT §15.1），且后端全仓零写入 5，
 *     此处**不固化**待修复行为，留 todo 待漂移修复后确定。
 *
 * 后端侧 status 过滤已由 `MerchantVoucherOrderQueryTest`（Case8=4 / Case9=6 /
 * Case10=5-空）覆盖，故本文件不再重复，只补前端视图断言。
 */

const routeState = vi.hoisted(() => ({ query: {} as Record<string, unknown> }));
vi.mock('vue-router', () => ({
  useRouter: () => ({ back: vi.fn(), push: vi.fn() }),
  useRoute: () => ({ query: routeState.query }),
}));

// 只替换命令式弹窗/提示，保留真实 Vant 组件供模板使用
vi.mock('vant', async () => {
  const actual = await vi.importActual<typeof import('vant')>('vant');
  return {
    ...actual,
    showConfirmDialog: vi.fn(),
    showToast: vi.fn(),
  };
});

vi.mock('@/api/merchantOrder', () => ({
  merchantOrderService: {
    queryMerchantOrders: vi.fn(),
    redeemMerchantOrder: vi.fn(),
  },
}));

const queryMerchantOrders = vi.mocked(merchantOrderService.queryMerchantOrders);

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
const statusText = (wrapper: Wrapper, index = 0) =>
  cards(wrapper)[index].find('[data-testid="order-status"]').text();

beforeEach(() => {
  vi.clearAllMocks();
  routeState.query = {};
});

describe('Merchant/OrderList.vue — 长尾状态文案（P2 补充）', () => {
  it('「全部」tab 下 status=4/6 的订单可见，文案分别为「已取消」/「已退款」', async () => {
    queryMerchantOrders.mockResolvedValue(
      okResult(
        [
          makeItem({ orderId: 'a', status: 4 as OrderStatus }),
          makeItem({ orderId: 'b', status: 6 as OrderStatus }),
        ],
        2
      ) as never
    );

    const wrapper = mountPage();
    await flushPromises();

    // 「全部」tab 不传 status（null / undefined 均视为全部）
    expect(queryMerchantOrders.mock.calls[0]?.[2] ?? null).toBeNull();
    expect(cards(wrapper)).toHaveLength(2);
    expect(statusText(wrapper, 0)).toBe('已取消');
    expect(statusText(wrapper, 1)).toBe('已退款');
  });

  it('status=4（已取消）单独渲染时文案正确', async () => {
    queryMerchantOrders.mockResolvedValue(
      okResult([makeItem({ orderId: 'c', status: 4 as OrderStatus })], 1) as never
    );

    const wrapper = mountPage();
    await flushPromises();

    expect(statusText(wrapper, 0)).toBe('已取消');
  });

  it('status=6（已退款）单独渲染时文案正确', async () => {
    queryMerchantOrders.mockResolvedValue(
      okResult([makeItem({ orderId: 'd', status: 6 as OrderStatus })], 1) as never
    );

    const wrapper = mountPage();
    await flushPromises();

    expect(statusText(wrapper, 0)).toBe('已退款');
  });

  // 后端 OrderStatus.EXPIRED=5 与前端「退款中」语义漂移，且后端零写入 5：
  // 修复方向见 docs/PROJECT_STATUS_REPORT.md §15.1 / docs/phase1/order-status-drift-impact-analysis.md
  it.todo('status=5 的商家视图文案（待「订单状态 5 语义漂移」修复后确定）');
});
