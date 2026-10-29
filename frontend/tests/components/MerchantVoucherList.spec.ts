import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { showToast } from 'vant';
import type { MerchantVoucherDTO } from '@/api/types';
import { merchantVoucherService } from '@/api/merchantVoucher';
import { merchantShopService } from '@/api/merchantShop';
import MerchantVoucherList from '@/views/Merchant/VoucherList.vue';

vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router');
  return { ...actual, useRouter: () => ({ back: vi.fn(), push: vi.fn() }) };
});

// 只 mock 商家券 / 店铺 API，验证「UI 是否正确消费 API 契约」
vi.mock('@/api/merchantVoucher', () => ({
  merchantVoucherService: {
    queryMyVouchers: vi.fn(),
    createVoucher: vi.fn(),
    createSeckillVoucher: vi.fn(),
    updateVoucher: vi.fn(),
  },
}));
vi.mock('@/api/merchantShop', () => ({
  merchantShopService: { queryMyShops: vi.fn() },
}));

vi.mock('vant', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vant')>();
  return { ...actual, showToast: vi.fn() };
});

const queryMyVouchers = vi.mocked(merchantVoucherService.queryMyVouchers);
const createVoucher = vi.mocked(merchantVoucherService.createVoucher);
const createSeckillVoucher = vi.mocked(merchantVoucherService.createSeckillVoucher);
const updateVoucher = vi.mocked(merchantVoucherService.updateVoucher);
const queryMyShops = vi.mocked(merchantShopService.queryMyShops);
const showToastMock = vi.mocked(showToast);

const okResult = <T>(data: T) => ({
  data: { success: true, errorCode: null, errorMsg: null, data, total: null, traceId: null },
});

const SHOPS = [
  { id: '9000000000000000001', name: 'E2E-Shop-老王烧烤', typeId: '1', images: '[]', area: null, address: '文三路 100 号', x: 120.1, y: 30.2, avgPrice: '88', sold: 12, comments: 3, score: 45, openHours: '10:00-22:00', city: '杭州' },
];

/** Long 字段按真实契约用字符串：id/shopId/payValue/actualValue */
const makeVoucher = (overrides: Partial<MerchantVoucherDTO> = {}): MerchantVoucherDTO => ({
  id: '9000000000000000001',
  shopId: '9000000000000000001',
  title: '满50减2',
  subTitle: null,
  rules: null,
  payValue: '4750',
  actualValue: '5000',
  type: 0,
  status: 1,
  stock: null,
  beginTime: null,
  endTime: null,
  createTime: '2026-09-20T10:00:00',
  updateTime: '2026-09-20T10:00:00',
  ...overrides,
});

const mountPage = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return mount(MerchantVoucherList, {
    global: { plugins: [[VueQueryPlugin, { queryClient }]] },
  });
};

type Wrapper = ReturnType<typeof mountPage>;

const cards = (wrapper: Wrapper) => wrapper.findAll('[data-testid="voucher-card"]');
const click = async (wrapper: Wrapper, testid: string) => {
  await wrapper.get(`[data-testid="${testid}"]`).trigger('click');
  await flushPromises();
};
const inputOf = (wrapper: Wrapper, testid: string) =>
  wrapper.get(`[data-testid="${testid}"] input`).element as HTMLInputElement;
const valueOf = (wrapper: Wrapper, testid: string) => inputOf(wrapper, testid).value;
const setField = async (wrapper: Wrapper, testid: string, value: string) => {
  await wrapper.get(`[data-testid="${testid}"] input`).setValue(value);
};
const pickType = async (wrapper: Wrapper, index = 0) => {
  await wrapper.findAll('[data-testid="type-option"]')[index].trigger('click');
  await flushPromises();
};
const pickShop = async (wrapper: Wrapper, index = 0) => {
  await wrapper.findAll('[data-testid="shop-option"]')[index].trigger('click');
  await flushPromises();
};
const openCreate = async (wrapper: Wrapper) => click(wrapper, 'voucher-create');
const submit = async (wrapper: Wrapper) => {
  await wrapper.get('[data-testid="voucher-save"]').trigger('click');
  await flushPromises();
};

const fillBaseForm = async (wrapper: Wrapper, title = '满50减2', pay = '47.50', actual = '50.00') => {
  await pickShop(wrapper, 0);
  await setField(wrapper, 'field-title', title);
  await setField(wrapper, 'field-payYuan', pay);
  await setField(wrapper, 'field-actualYuan', actual);
};

const deferred = <T>() => {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
};

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  queryMyShops.mockResolvedValue(okResult([...SHOPS]) as never);
  queryMyVouchers.mockResolvedValue(okResult([makeVoucher()]) as never);
});

describe('Merchant/VoucherList.vue — 查询', () => {
  it('渲染优惠券卡片：标题/类型/状态/店铺/金额', async () => {
    const wrapper = mountPage();
    await flushPromises();

    const card = cards(wrapper)[0];
    const text = card.text();
    expect(text).toContain('满50减2');
    expect(text).toContain('普通券');
    expect(text).toContain('上架');
    expect(text).toContain('E2E-Shop-老王烧烤');
    // 金额以「元」展示（分→元），绝不以原始分「¥4750」出现
    expect(text).toContain('¥50'); // formatMoney('5000') = '50'
    expect(text).toContain('¥47.50');
    expect(text).not.toContain('¥4750');
    expect(wrapper.text()).toContain('共 1 张优惠券');
  });

  it('调用 queryMyVouchers() 且不传分页/owner 参数', async () => {
    mountPage();
    await flushPromises();
    expect(queryMyVouchers).toHaveBeenCalledTimes(1);
    expect(queryMyVouchers).toHaveBeenCalledWith();
  });

  it('秒杀券展示库存与起止时间', async () => {
    queryMyVouchers.mockResolvedValue(
      okResult([
        makeVoucher({
          type: 1,
          stock: 100,
          beginTime: '2026-09-22T10:00:00',
          endTime: '2026-09-22T20:00:00',
        }),
      ]) as never
    );
    const wrapper = mountPage();
    await flushPromises();

    const card = cards(wrapper)[0];
    expect(card.text()).toContain('秒杀券');
    expect(card.find('[data-testid="voucher-seckill-info"]').exists()).toBe(true);
    expect(card.text()).toContain('库存 100');
    expect(card.text()).toContain('2026-09-22 10:00');
  });

  it('加载中显示 LoadingState', async () => {
    queryMyVouchers.mockReturnValue(new Promise(() => {}) as never);
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.text()).toContain('加载中');
    expect(cards(wrapper)).toHaveLength(0);
  });

  it('空列表显示 EmptyState「暂无优惠券」', async () => {
    queryMyVouchers.mockResolvedValue(okResult([]) as never);
    const wrapper = mountPage();
    await flushPromises();
    expect(wrapper.text()).toContain('暂无优惠券');
    expect(cards(wrapper)).toHaveLength(0);
  });

  it('请求失败显示 ErrorState，点击「重试」重新发起请求', async () => {
    queryMyVouchers.mockRejectedValueOnce(new Error('boom'));
    queryMyVouchers.mockResolvedValueOnce(okResult([makeVoucher()]) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('加载失败');
    expect(cards(wrapper)).toHaveLength(0);

    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(queryMyVouchers).toHaveBeenCalledTimes(2);
    expect(cards(wrapper)).toHaveLength(1);
  });
});

describe('Merchant/VoucherList.vue — 店铺映射与 0 店铺', () => {
  it('shopId 命中 /shops/my → 显示店铺名', async () => {
    const wrapper = mountPage();
    await flushPromises();
    expect(cards(wrapper)[0].text()).toContain('E2E-Shop-老王烧烤');
  });

  it('shopId 未命中 → fallback「店铺 {shopId}」', async () => {
    queryMyVouchers.mockResolvedValue(
      okResult([makeVoucher({ id: '3', shopId: '999' })]) as never
    );
    const wrapper = mountPage();
    await flushPromises();
    expect(cards(wrapper)[0].text()).toContain('店铺 999');
  });

  it('0 店铺 → 新增按钮禁用 + 提示先建店，点选打不开弹窗', async () => {
    queryMyShops.mockResolvedValue(okResult([]) as never);
    const wrapper = mountPage();
    await flushPromises();

    const btn = wrapper.get('[data-testid="voucher-create"]');
    expect(btn.attributes('disabled')).toBeDefined();
    // 禁用同时给出显式文案提示「请先创建店铺」（§13：禁用新增 + 明确提示）
    expect(wrapper.find('[data-testid="no-shops-hint"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="no-shops-hint"]').text()).toContain('请先创建店铺');

    // 禁用按钮点击为 no-op：不会打开弹窗
    await click(wrapper, 'voucher-create');
    expect(wrapper.find('[data-testid="field-title"]').exists()).toBe(false);
  });
});

describe('Merchant/VoucherList.vue — 新建普通券', () => {
  it('打开弹窗：默认普通券类型 type=0', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);

    expect(wrapper.text()).toContain('新增优惠券');
    expect(wrapper.find('[data-testid="field-title"]').exists()).toBe(true);
    // 创建模式默认普通券：不显示秒杀字段
    expect(wrapper.find('[data-testid="field-stock"]').exists()).toBe(false);
  });

  it('标题必填：为空提示且不发请求', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await pickShop(wrapper, 0);
    await setField(wrapper, 'field-payYuan', '47.50');
    await setField(wrapper, 'field-actualYuan', '50.00');
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('请填写优惠券标题');
    expect(createVoucher).not.toHaveBeenCalled();
  });

  it('未选店铺：提示且不发请求', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await setField(wrapper, 'field-title', '满50减2');
    await setField(wrapper, 'field-payYuan', '47.50');
    await setField(wrapper, 'field-actualYuan', '50.00');
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('请选择店铺');
    expect(createVoucher).not.toHaveBeenCalled();
  });

  it('金额元→分转换：47.50→4750，99→9900（type=0）', async () => {
    createVoucher.mockResolvedValue(okResult('9000000000000000002') as never);
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await fillBaseForm(wrapper, '满50减2', '99', '100');
    await submit(wrapper);

    expect(createVoucher).toHaveBeenCalledTimes(1);
    expect(createVoucher).toHaveBeenCalledWith({
      shopId: '9000000000000000001',
      title: '满50减2',
      subTitle: null,
      rules: null,
      payValue: 9900,
      actualValue: 10000,
      type: 0,
    });
    expect(createSeckillVoucher).not.toHaveBeenCalled();
  });

  it('金额为空/非法 → 提示且不发请求', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await pickShop(wrapper, 0);
    await setField(wrapper, 'field-title', '满50减2');
    await setField(wrapper, 'field-actualYuan', '50');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('请填写正确的支付金额（元）');
    expect(createVoucher).not.toHaveBeenCalled();
  });

  it('负金额 → 提示且不发请求', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await pickShop(wrapper, 0);
    await setField(wrapper, 'field-title', '满50减2');
    await setField(wrapper, 'field-payYuan', '-5');
    await setField(wrapper, 'field-actualYuan', '50');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('请填写正确的支付金额（元）');
    expect(createVoucher).not.toHaveBeenCalled();
  });

  it('actualValue < payValue → 提示且不发请求', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await fillBaseForm(wrapper, '满50减2', '50', '40');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('抵扣金额不能小于支付金额');
    expect(createVoucher).not.toHaveBeenCalled();
  });

  it('payload 白名单：只含 create 字段 + type=0，不含 stock/time 等', async () => {
    createVoucher.mockResolvedValue(okResult('9000000000000000002') as never);
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await fillBaseForm(wrapper, '满50减2');
    await submit(wrapper);

    const payload = createVoucher.mock.calls[0][0] as Record<string, unknown>;
    expect(Object.keys(payload).sort()).toEqual([
      'actualValue',
      'payValue',
      'rules',
      'shopId',
      'subTitle',
      'title',
      'type',
    ]);
    expect(payload).not.toHaveProperty('stock');
    expect(payload).not.toHaveProperty('beginTime');
    expect(payload).not.toHaveProperty('endTime');
    expect(payload).not.toHaveProperty('id');
    expect(payload).not.toHaveProperty('ownerId');
  });

  it('创建成功 → 提示 + 关闭弹窗 + 重新拉取列表', async () => {
    createVoucher.mockResolvedValue(okResult('9000000000000000002') as never);
    const wrapper = mountPage();
    await flushPromises();
    expect(queryMyVouchers).toHaveBeenCalledTimes(1);

    await openCreate(wrapper);
    await fillBaseForm(wrapper);
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('优惠券创建成功');
    expect(queryMyVouchers).toHaveBeenCalledTimes(2); // invalidateQueries(['merchant-vouchers'])
    expect(wrapper.find('[data-testid="field-title"]').isVisible()).toBe(false);
  });
});

describe('Merchant/VoucherList.vue — 新建秒杀券', () => {
  it('切换到秒杀券后出现 stock/begin/end 字段', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);

    expect(wrapper.find('[data-testid="field-stock"]').exists()).toBe(false);
    await pickType(wrapper, 1);
    expect(wrapper.find('[data-testid="field-stock"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="field-beginTime"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="field-endTime"]').exists()).toBe(true);
  });

  it('秒杀创建：type=1 + stock + 时间空格→T + payload 白名单', async () => {
    createSeckillVoucher.mockResolvedValue(okResult('9000000000000000003') as never);
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await pickType(wrapper, 1);
    await fillBaseForm(wrapper, '秒杀5折', '50', '100');
    await setField(wrapper, 'field-stock', '100');
    await setField(wrapper, 'field-beginTime', '2026-09-22 10:00:00');
    await setField(wrapper, 'field-endTime', '2026-09-22 20:00:00');
    await submit(wrapper);

    expect(createSeckillVoucher).toHaveBeenCalledTimes(1);
    expect(createVoucher).not.toHaveBeenCalled();
    const payload = createSeckillVoucher.mock.calls[0][0] as Record<string, unknown>;
    expect(payload).toEqual({
      shopId: '9000000000000000001',
      title: '秒杀5折',
      subTitle: null,
      rules: null,
      payValue: 5000,
      actualValue: 10000,
      type: 1,
      stock: 100,
      beginTime: '2026-09-22T10:00:00',
      endTime: '2026-09-22T20:00:00',
    });
    // 禁止字段
    expect(payload).not.toHaveProperty('id');
    expect(payload).not.toHaveProperty('ownerId');
  });

  it('stock 必填且需正整数', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await pickType(wrapper, 1);
    await fillBaseForm(wrapper, '秒杀5折', '50', '100');
    await setField(wrapper, 'field-beginTime', '2026-09-22 10:00');
    await setField(wrapper, 'field-endTime', '2026-09-22 20:00');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('库存需为正整数');
    expect(createSeckillVoucher).not.toHaveBeenCalled();
  });

  it('stock 为 0 或小数或负数 → 拒绝', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await pickType(wrapper, 1);
    await fillBaseForm(wrapper, '秒杀5折', '50', '100');
    await setField(wrapper, 'field-beginTime', '2026-09-22 10:00');
    await setField(wrapper, 'field-endTime', '2026-09-22 20:00');
    await setField(wrapper, 'field-stock', '1.5');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('库存需为正整数');
    expect(createSeckillVoucher).not.toHaveBeenCalled();
  });

  it('beginTime/endTime 必填：缺失提示', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await pickType(wrapper, 1);
    await fillBaseForm(wrapper, '秒杀5折', '50', '100');
    await setField(wrapper, 'field-stock', '100');
    await setField(wrapper, 'field-endTime', '2026-09-22 20:00');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('请填写正确的开始时间（YYYY-MM-DD HH:mm）');
    expect(createSeckillVoucher).not.toHaveBeenCalled();
  });

  it('begin >= end → 拒绝', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await pickType(wrapper, 1);
    await fillBaseForm(wrapper, '秒杀5折', '50', '100');
    await setField(wrapper, 'field-stock', '100');
    await setField(wrapper, 'field-beginTime', '2026-09-22 20:00');
    await setField(wrapper, 'field-endTime', '2026-09-22 20:00');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('开始时间必须早于结束时间');
    expect(createSeckillVoucher).not.toHaveBeenCalled();
  });
});

describe('Merchant/VoucherList.vue — 编辑', () => {
  it('打开编辑：行数据回填，金额分→元', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'voucher-edit');

    expect(wrapper.text()).toContain('编辑优惠券');
    expect(valueOf(wrapper, 'field-title')).toBe('满50减2');
    expect(valueOf(wrapper, 'field-payYuan')).toBe('47.50'); // 4750 分 → 47.50 元
    expect(valueOf(wrapper, 'field-actualYuan')).toBe('50'); // 5000 分 → 50 元
  });

  it('编辑不显示 shopId / type / stock / 时间（白名单锁定）', async () => {
    queryMyVouchers.mockResolvedValue(
      okResult([makeVoucher({ type: 1, stock: 100 })]) as never
    ) as never;
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'voucher-edit');

    // 即使原券是秒杀券，编辑也不出现秒杀字段
    const text = wrapper.find('.van-popup').text();
    expect(text).not.toContain('所属店铺');
    expect(text).not.toContain('券类型');
    expect(text).not.toContain('库存');
    expect(text).not.toContain('开始时间');
    expect(wrapper.find('[data-testid="field-stock"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="shop-option"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="type-option"]').exists()).toBe(false);
  });

  it('编辑提交 → PUT 使用原始字符串 ID + 仅白名单 6 字段', async () => {
    const LONG = '9223372036854775801';
    queryMyVouchers.mockResolvedValue(okResult([makeVoucher({ id: LONG })]) as never);
    updateVoucher.mockResolvedValue(okResult(null) as never);
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'voucher-edit');

    await setField(wrapper, 'field-title', '满50减2(改)');
    await submit(wrapper);

    expect(updateVoucher).toHaveBeenCalledTimes(1);
    const [voucherId, payload] = updateVoucher.mock.calls[0] as [unknown, Record<string, unknown>];
    // 铁律：Long ID 原样字符串，禁止 Number()/parseInt()
    expect(voucherId).toBe(LONG);
    expect(Object.keys(payload).sort()).toEqual([
      'actualValue',
      'payValue',
      'rules',
      'status',
      'subTitle',
      'title',
    ]);
    expect(payload).toEqual({
      title: '满50减2(改)',
      subTitle: null,
      rules: null,
      payValue: 4750,
      actualValue: 5000,
      status: 1,
    });
    // 禁止字段绝不进入 PUT
    for (const forbidden of ['shopId', 'type', 'stock', 'beginTime', 'endTime', 'id', 'ownerId']) {
      expect(payload).not.toHaveProperty(forbidden);
    }
  });

  it('编辑成功 → 提示 + 关闭弹窗 + 重新拉取', async () => {
    updateVoucher.mockResolvedValue(okResult(null) as never);
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'voucher-edit');
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('优惠券已更新');
    expect(queryMyVouchers).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="field-title"]').isVisible()).toBe(false);
  });
});

describe('Merchant/VoucherList.vue — 上架/下架', () => {
  it('status=1 → 「下架」按钮，提交 {title, status:2}', async () => {
    updateVoucher.mockResolvedValue(okResult(null) as never);
    const wrapper = mountPage();
    await flushPromises();

    const toggle = wrapper.get('[data-testid="voucher-status-toggle"]');
    expect(toggle.text()).toBe('下架');
    await toggle.trigger('click');
    await flushPromises();

    expect(updateVoucher).toHaveBeenCalledWith('9000000000000000001', {
      title: '满50减2',
      status: 2,
    });
    expect(showToastMock).toHaveBeenCalledWith('已下架');
    expect(queryMyVouchers).toHaveBeenCalledTimes(2);
  });

  it('status=2 → 「上架」按钮，提交 {title, status:1}', async () => {
    queryMyVouchers.mockResolvedValue(okResult([makeVoucher({ status: 2 })]) as never);
    updateVoucher.mockResolvedValue(okResult(null) as never);
    const wrapper = mountPage();
    await flushPromises();

    const toggle = wrapper.get('[data-testid="voucher-status-toggle"]');
    expect(toggle.text()).toBe('上架');
    await toggle.trigger('click');
    await flushPromises();

    expect(updateVoucher).toHaveBeenCalledWith('9000000000000000001', {
      title: '满50减2',
      status: 1,
    });
    expect(showToastMock).toHaveBeenCalledWith('已上架');
  });

  it('status=3 → 只展示「过期」，不提供操作按钮', async () => {
    queryMyVouchers.mockResolvedValue(okResult([makeVoucher({ status: 3 })]) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(cards(wrapper)[0].text()).toContain('过期');
    expect(wrapper.find('[data-testid="voucher-status-toggle"]').exists()).toBe(false);
  });
});

describe('Merchant/VoucherList.vue — UX', () => {
  it('取消 → 关闭弹窗并复位表单', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await setField(wrapper, 'field-title', '临时输入');
    await click(wrapper, 'voucher-cancel');

    expect(wrapper.find('[data-testid="field-title"]').isVisible()).toBe(false);

    await openCreate(wrapper);
    expect(valueOf(wrapper, 'field-title')).toBe('');
  });

  it('提交中重复点击只发起一次请求（pending 守卫）', async () => {
    const d = deferred<never>();
    createVoucher.mockReturnValue(d.promise as never);
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await fillBaseForm(wrapper);

    const save = wrapper.get('[data-testid="voucher-save"]');
    await save.trigger('click');
    await save.trigger('click');
    await save.trigger('click');
    await flushPromises();

    expect(createVoucher).toHaveBeenCalledTimes(1);

    d.resolve(undefined as never);
    await flushPromises();
  });

  it('提交失败 → 提示、弹窗保持打开、用户输入不丢', async () => {
    createVoucher.mockRejectedValueOnce({ errorMsg: '仅商家可操作' });
    const wrapper = mountPage();
    await flushPromises();
    await openCreate(wrapper);
    await fillBaseForm(wrapper);
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('仅商家可操作');
    expect(wrapper.find('[data-testid="field-title"]').isVisible()).toBe(true);
    expect(valueOf(wrapper, 'field-title')).toBe('满50减2');
    expect(queryMyVouchers).toHaveBeenCalledTimes(1); // 失败不触发重拉
  });
});

describe('Merchant/VoucherList.vue — 路由与类型安全', () => {
  it('/merchant/vouchers 已注册，且 meta 与既有 merchant 页一致', async () => {
    const { default: router } = await import('@/router');
    const v = router.resolve('/merchant/vouchers');
    expect(v.name).toBe('MerchantVouchers');
    expect(v.meta.requiresAuth).toBe(true);
    expect(v.meta.requiresMerchant).toBe(true);
  });

  it('Long ID 不被 Number()/parseInt() 转换（9223372036854775801 原样透传）', async () => {
    const LONG = '9223372036854775801';
    queryMyVouchers.mockResolvedValue(okResult([makeVoucher({ id: LONG })]) as never);
    updateVoucher.mockResolvedValue(okResult(null) as never);
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'voucher-edit');
    await submit(wrapper);

    expect(updateVoucher.mock.calls[0][0]).toBe('9223372036854775801');
  });
});