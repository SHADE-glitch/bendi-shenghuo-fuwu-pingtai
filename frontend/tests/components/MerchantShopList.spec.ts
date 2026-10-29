import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import { QueryClient, VueQueryPlugin } from '@tanstack/vue-query';
import { showToast } from 'vant';
import type { MerchantShopDTO } from '@/api/types';
import { merchantShopService } from '@/api/merchantShop';
import { shopService } from '@/api/shop';
import MerchantShopList from '@/views/Merchant/ShopList.vue';

// 页面只用到 router.back()；保留 vue-router 其余真实导出（下面要用 router 实例断言路由表）
vi.mock('vue-router', async () => {
  const actual = await vi.importActual<typeof import('vue-router')>('vue-router');
  return { ...actual, useRouter: () => ({ back: vi.fn(), push: vi.fn() }) };
});

// 只 mock 商家店铺 API，验证「UI 是否正确消费 API 契约」
// （后端权限链 assertMerchant/assertOwner 由后端与后续 Shop E2E 覆盖）
vi.mock('@/api/merchantShop', () => ({
  merchantShopService: {
    queryMyShops: vi.fn(),
    createShop: vi.fn(),
    updateShop: vi.fn(),
  },
}));

// 分类名映射复用既有 shopService.queryTypeList()（本页面不新增分类接口）
vi.mock('@/api/shop', () => ({
  shopService: { queryTypeList: vi.fn() },
}));

// 只替换 showToast，保留 Vant 其余导出（tests/setup.ts 全局注册依赖 default export）
vi.mock('vant', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vant')>();
  return { ...actual, showToast: vi.fn() };
});

const queryMyShops = vi.mocked(merchantShopService.queryMyShops);
const createShop = vi.mocked(merchantShopService.createShop);
const updateShop = vi.mocked(merchantShopService.updateShop);
const queryTypeList = vi.mocked(shopService.queryTypeList);
const showToastMock = vi.mocked(showToast);

const okResult = <T>(data: T) => ({
  data: { success: true, errorCode: null, errorMsg: null, data, total: null, traceId: null },
});

const okTypes = [
  { id: 1, name: '美食', icon: 'a', sort: 1 },
  { id: 2, name: 'KTV', icon: 'b', sort: 2 },
];

/** 字段类型按 Phase 4.6 Step 1 的真实响应构造：Long（id/typeId/avgPrice）为字符串 */
const makeShop = (overrides: Partial<MerchantShopDTO> = {}): MerchantShopDTO => ({
  id: '9000000000000000001',
  name: 'E2E-Shop-老王烧烤',
  city: '杭州',
  typeId: '1',
  images: '[]',
  area: '西湖区',
  address: '文三路 100 号',
  x: 120.1,
  y: 30.2,
  avgPrice: '88',
  sold: 12,
  comments: 3,
  score: 45,
  openHours: '10:00-22:00',
  ...overrides,
});

const mountPage = () => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return mount(MerchantShopList, {
    global: { plugins: [[VueQueryPlugin, { queryClient }]] },
  });
};

type Wrapper = ReturnType<typeof mountPage>;

const cards = (wrapper: Wrapper) => wrapper.findAll('[data-testid="merchant-shop-card"]');
const typeTag = (wrapper: Wrapper, index = 0) =>
  cards(wrapper)[index].find('[data-testid="shop-type"]').text();

// ---------------- 表单交互辅助 ----------------

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

/** 填一份合法表单（分类取 okTypes[0] = 美食 id=1） */
const fillValidForm = async (wrapper: Wrapper) => {
  await setField(wrapper, 'field-name', '老王烧烤');
  await pickType(wrapper, 0);
  await setField(wrapper, 'field-address', '文三路 100 号');
  await setField(wrapper, 'field-openHours', '10:00-22:00');
  await setField(wrapper, 'field-x', '120.155');
  await setField(wrapper, 'field-y', '30.275');
};

const submit = async (wrapper: Wrapper) => {
  await wrapper.get('[data-testid="shop-save"]').trigger('click');
  await flushPromises();
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

/** 期望的 6 个可编辑字段（§15/§16：新增与更新共用同一份显式 payload） */
const EXPECTED_KEYS = ['address', 'name', 'openHours', 'typeId', 'x', 'y'];

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  queryTypeList.mockResolvedValue(okResult(okTypes) as never);
  queryMyShops.mockResolvedValue(okResult([makeShop()]) as never);
});

describe('Merchant/ShopList.vue — 查询（Phase 4.6 Step 1）', () => {
  it('渲染店铺卡片：名称 / 地址 / 营业时间 / 评分 / 销量 / 评价数', async () => {
    const wrapper = mountPage();
    await flushPromises();

    const card = cards(wrapper)[0];
    const text = card.text();
    expect(text).toContain('E2E-Shop-老王烧烤');
    expect(text).toContain('文三路 100 号');
    expect(text).toContain('营业时间：10:00-22:00');
    expect(text).toContain('4.5 分'); // formatScore(45) = 4.5
    expect(text).toContain('月售 12');
    expect(text).toContain('评价 3');
    expect(wrapper.text()).toContain('共 1 个店铺');
  });

  it('调用 queryMyShops() 且不传任何 owner 参数', async () => {
    mountPage();
    await flushPromises();

    expect(queryMyShops).toHaveBeenCalledTimes(1);
    expect(queryMyShops).toHaveBeenCalledWith();
  });

  it('店铺 ID 以字符串原样展示，未被 Number 精度转换', async () => {
    queryMyShops.mockResolvedValue(okResult([makeShop({ id: '9000000000000000001' })]) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(cards(wrapper)[0].text()).toContain('9000000000000000001');
  });

  it('分类名来自 /shop-types 映射（复用 shopTypes 缓存键）', async () => {
    queryMyShops.mockResolvedValue(
      okResult([makeShop({ id: 'a', typeId: '2' }), makeShop({ id: 'b', typeId: '1' })]) as never
    );
    const wrapper = mountPage();
    await flushPromises();

    expect(typeTag(wrapper, 0)).toBe('KTV');
    expect(typeTag(wrapper, 1)).toBe('美食');
  });

  it('类型表尚未就绪时退回显示「分类 {id}」，不阻塞列表', async () => {
    queryTypeList.mockResolvedValue(okResult([]) as never);
    queryMyShops.mockResolvedValue(okResult([makeShop({ typeId: '7' })]) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(typeTag(wrapper)).toBe('分类 7');
    expect(cards(wrapper)).toHaveLength(1);
  });

  it('营业时间为空时显示「未设置」', async () => {
    queryMyShops.mockResolvedValue(okResult([makeShop({ openHours: null })]) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(cards(wrapper)[0].text()).toContain('营业时间：未设置');
  });

  it('空列表显示 EmptyState「暂无店铺」', async () => {
    queryMyShops.mockResolvedValue(okResult([]) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('暂无店铺');
    expect(cards(wrapper)).toHaveLength(0);
  });

  it('加载中显示 LoadingState', async () => {
    queryMyShops.mockReturnValue(new Promise(() => {}) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('加载中');
    expect(cards(wrapper)).toHaveLength(0);
  });

  it('请求失败显示 ErrorState，点击「重试」重新发起请求', async () => {
    queryMyShops.mockRejectedValueOnce(new Error('boom'));
    queryMyShops.mockResolvedValueOnce(okResult([makeShop()]) as never);

    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.text()).toContain('加载失败');
    expect(cards(wrapper)).toHaveLength(0);

    await wrapper.get('button').trigger('click');
    await flushPromises();

    expect(queryMyShops).toHaveBeenCalledTimes(2);
    expect(cards(wrapper)).toHaveLength(1);
    expect(wrapper.text()).toContain('E2E-Shop-老王烧烤');
  });

  it('多店铺按后端顺序全部渲染（不做前端排序）', async () => {
    queryMyShops.mockResolvedValue(
      okResult([
        makeShop({ id: '2', name: '第二家' }),
        makeShop({ id: '1', name: '第一家' }),
      ]) as never
    );
    const wrapper = mountPage();
    await flushPromises();

    expect(cards(wrapper)).toHaveLength(2);
    expect(cards(wrapper)[0].text()).toContain('第二家');
    expect(cards(wrapper)[1].text()).toContain('第一家');
  });
});

describe('Merchant/ShopList.vue — 新增店铺（Phase 4.6 Step 2）', () => {
  it('1. 顶部「新增店铺」打开弹窗，标题为「新增店铺」', async () => {
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="field-name"]').exists()).toBe(false);

    await click(wrapper, 'shop-create-top');

    expect(wrapper.text()).toContain('新增店铺');
    expect(wrapper.text()).not.toContain('编辑店铺');
    expect(wrapper.find('[data-testid="field-name"]').exists()).toBe(true);
  });

  it('2. 空态内的「新增店铺」同样能打开弹窗（复用 EmptyState 插槽）', async () => {
    queryMyShops.mockResolvedValue(okResult([]) as never);
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.find('[data-testid="shop-create-top"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="shop-create-empty"]').exists()).toBe(true);

    await click(wrapper, 'shop-create-empty');

    expect(wrapper.find('[data-testid="field-name"]').exists()).toBe(true);
  });

  it('3. 新增默认坐标取当前城市（默认杭州 = DEFAULT_LOCATION）', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');

    expect(valueOf(wrapper, 'field-x')).toBe('120.155');
    expect(valueOf(wrapper, 'field-y')).toBe('30.275');
    expect(valueOf(wrapper, 'field-name')).toBe('');
    expect(valueOf(wrapper, 'field-address')).toBe('');
    expect(valueOf(wrapper, 'field-openHours')).toBe('');
  });

  it('4. 新增默认坐标跟随 localStorage 中选定的城市（北京）', async () => {
    localStorage.setItem('loc_city', '北京');
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');

    expect(valueOf(wrapper, 'field-x')).toBe('116.4074');
    expect(valueOf(wrapper, 'field-y')).toBe('39.9042');
  });

  it('5. 店铺名称为空 → 提示且不发请求', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('请填写店铺名称');
    expect(createShop).not.toHaveBeenCalled();
  });

  it('6. 店铺名称仅空格 → 视为空（trim 后校验）', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');
    await setField(wrapper, 'field-name', '   ');
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('请填写店铺名称');
    expect(createShop).not.toHaveBeenCalled();
  });

  it('7. 店铺名称长度上限 128：由字段 maxlength 强制截断', async () => {
    // 说明：van-field 的 maxlength 在 JS 层截断（textarea 同样生效），
    // 因此「超过 128」在 UI 上不可达；页面里的 length 校验是纵深防御。
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');

    await setField(wrapper, 'field-name', 'a'.repeat(129));
    expect(valueOf(wrapper, 'field-name')).toHaveLength(128);

    // 恰好 128 字符可通过长度校验 → 继续命中「未选分类」
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('请选择店铺分类');
    expect(createShop).not.toHaveBeenCalled();
  });

  it('8. 未选择店铺分类 → 提示且不发请求', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');
    await setField(wrapper, 'field-name', '老王烧烤');
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('请选择店铺分类');
    expect(createShop).not.toHaveBeenCalled();
  });

  it('9. 店铺地址必填；长度上限 255 由字段 maxlength 强制截断', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');
    await setField(wrapper, 'field-name', '老王烧烤');
    await pickType(wrapper, 0);
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('请填写店铺地址');

    await setField(wrapper, 'field-address', 'a'.repeat(256));
    expect(valueOf(wrapper, 'field-address')).toHaveLength(255);

    expect(createShop).not.toHaveBeenCalled();
  });

  it('10. 营业时间必填；长度上限 32 由字段 maxlength 强制截断', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');
    await setField(wrapper, 'field-name', '老王烧烤');
    await pickType(wrapper, 0);
    await setField(wrapper, 'field-address', '文三路 100 号');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('请填写营业时间');

    await setField(wrapper, 'field-openHours', 'a'.repeat(33));
    expect(valueOf(wrapper, 'field-openHours')).toHaveLength(32);

    expect(createShop).not.toHaveBeenCalled();
  });

  it('11. 经度 / 纬度非数字 → 提示且不发请求', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');
    await fillValidForm(wrapper);

    await setField(wrapper, 'field-x', 'abc');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('请填写正确的经度');

    await setField(wrapper, 'field-x', '120.155');
    await setField(wrapper, 'field-y', 'abc');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('请填写正确的纬度');

    expect(createShop).not.toHaveBeenCalled();
  });

  it('12. 坐标越界（x>180 / y>90）→ 提示且不发请求', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');
    await fillValidForm(wrapper);

    await setField(wrapper, 'field-x', '180.1');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('经度需在 0 ~ 180 之间');

    await setField(wrapper, 'field-x', '120.155');
    await setField(wrapper, 'field-y', '90.1');
    await submit(wrapper);
    expect(showToastMock).toHaveBeenCalledWith('纬度需在 0 ~ 90 之间');

    expect(createShop).not.toHaveBeenCalled();
  });

  it('13. 合法提交 → createShop 收到且仅收到 6 个字段', async () => {
    createShop.mockResolvedValue(okResult('4211') as never);
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');
    await fillValidForm(wrapper);
    await submit(wrapper);

    expect(createShop).toHaveBeenCalledTimes(1);
    const payload = createShop.mock.calls[0][0] as Record<string, unknown>;
    expect(Object.keys(payload).sort()).toEqual(EXPECTED_KEYS);
    expect(payload).toEqual({
      name: '老王烧烤',
      typeId: '1',
      address: '文三路 100 号',
      openHours: '10:00-22:00',
      x: 120.155,
      y: 30.275,
    });
    // 服务端管理字段与归属字段一律不得出现
    for (const forbidden of ['ownerId', 'images', 'sold', 'comments', 'score', 'id']) {
      expect(payload).not.toHaveProperty(forbidden);
    }
  });

  it('14. 新增成功 → 提示 + 关闭弹窗 + 重新拉取列表', async () => {
    createShop.mockResolvedValue(okResult('4211') as never);
    const wrapper = mountPage();
    await flushPromises();
    expect(queryMyShops).toHaveBeenCalledTimes(1);

    await click(wrapper, 'shop-create-top');
    await fillValidForm(wrapper);
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('店铺创建成功');
    expect(queryMyShops).toHaveBeenCalledTimes(2); // invalidateQueries(['merchant-shops'])
    expect(wrapper.find('[data-testid="field-name"]').isVisible()).toBe(false);
  });
});

describe('Merchant/ShopList.vue — 编辑店铺（Phase 4.6 Step 2）', () => {
  it('15. 卡片「编辑」打开弹窗，标题「编辑店铺」并由列表行数据预填', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-edit');

    expect(wrapper.text()).toContain('编辑店铺');
    expect(valueOf(wrapper, 'field-name')).toBe('E2E-Shop-老王烧烤');
    expect(valueOf(wrapper, 'field-address')).toBe('文三路 100 号');
    expect(valueOf(wrapper, 'field-openHours')).toBe('10:00-22:00');
    expect(valueOf(wrapper, 'field-x')).toBe('120.1');
    expect(valueOf(wrapper, 'field-y')).toBe('30.2');
  });

  it('16. 编辑不额外请求详情（不调 createShop/updateShop，queryMyShops 仍只 1 次）', async () => {
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-edit');

    expect(queryMyShops).toHaveBeenCalledTimes(1);
    expect(createShop).not.toHaveBeenCalled();
    expect(updateShop).not.toHaveBeenCalled();
  });

  it('17. 编辑时 typeId 以 String 回填并命中对应单选项', async () => {
    queryMyShops.mockResolvedValue(okResult([makeShop({ typeId: '2' })]) as never);
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-edit');

    const options = wrapper.findAll('[data-testid="type-option"]');
    // Vant 把 checked 类挂在内层 icon 上，根节点暴露 aria-checked
    expect(options[0].attributes('aria-checked')).toBe('false');
    expect(options[1].attributes('aria-checked')).toBe('true'); // id=2 → KTV
  });

  it('18. 编辑提交 → updateShop(id, payload)，payload 同样只有 6 个字段', async () => {
    updateShop.mockResolvedValue(okResult(null) as never);
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-edit');

    await setField(wrapper, 'field-name', '老王烧烤(改)');
    await setField(wrapper, 'field-openHours', '09:00-21:30');
    await submit(wrapper);

    expect(updateShop).toHaveBeenCalledTimes(1);
    const [shopId, payload] = updateShop.mock.calls[0] as [unknown, Record<string, unknown>];
    expect(shopId).toBe('9000000000000000001');
    expect(Object.keys(payload).sort()).toEqual(EXPECTED_KEYS);
    expect(payload).toEqual({
      name: '老王烧烤(改)',
      typeId: '1',
      address: '文三路 100 号',
      openHours: '09:00-21:30',
      x: 120.1,
      y: 30.2,
    });
    for (const forbidden of ['ownerId', 'images', 'sold', 'comments', 'score', 'id']) {
      expect(payload).not.toHaveProperty(forbidden);
    }
  });

  it('19. 编辑时店铺 id 以字符串原样透传，未丢精度', async () => {
    updateShop.mockResolvedValue(okResult(null) as never);
    queryMyShops.mockResolvedValue(okResult([makeShop({ id: '9000000000000000001' })]) as never);
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-edit');
    await submit(wrapper);

    expect(updateShop.mock.calls[0][0]).toBe('9000000000000000001');
  });

  it('20. 编辑成功 → 提示「店铺已更新」+ 关闭弹窗 + 重新拉取列表', async () => {
    updateShop.mockResolvedValue(okResult(null) as never);
    const wrapper = mountPage();
    await flushPromises();

    await click(wrapper, 'shop-edit');
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('店铺已更新');
    expect(queryMyShops).toHaveBeenCalledTimes(2);
    expect(wrapper.find('[data-testid="field-name"]').isVisible()).toBe(false);
  });
});

describe('Merchant/ShopList.vue — 表单 UX（Phase 4.6 Step 2）', () => {
  it('21. 取消 → 关闭弹窗并复位表单（再次打开为干净表单）', async () => {
    const wrapper = mountPage();
    await flushPromises();

    await click(wrapper, 'shop-create-top');
    await setField(wrapper, 'field-name', '临时输入');
    await pickType(wrapper, 0);
    await click(wrapper, 'shop-cancel');

    expect(wrapper.find('[data-testid="field-name"]').isVisible()).toBe(false);

    await click(wrapper, 'shop-create-top');
    expect(valueOf(wrapper, 'field-name')).toBe('');
    expect(wrapper.findAll('[data-testid="type-option"]')[0].attributes('aria-checked')).toBe(
      'false'
    );
    // 坐标回到默认值而非上一次输入
    expect(valueOf(wrapper, 'field-x')).toBe('120.155');
  });

  it('22. 提交进行中重复点击只发起一次请求（pending 守卫）', async () => {
    const d = deferred<never>();
    createShop.mockReturnValue(d.promise as never);

    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');
    await fillValidForm(wrapper);

    const save = wrapper.get('[data-testid="shop-save"]');
    await save.trigger('click');
    await save.trigger('click');
    await save.trigger('click');
    await flushPromises();

    expect(createShop).toHaveBeenCalledTimes(1);

    d.resolve(undefined as never);
    await flushPromises();
  });

  it('23. 提交失败 → 提示、弹窗保持打开、用户输入不丢', async () => {
    createShop.mockRejectedValueOnce({ errorMsg: '仅商家可操作' });
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-create-top');
    await fillValidForm(wrapper);
    await submit(wrapper);

    expect(showToastMock).toHaveBeenCalledWith('仅商家可操作');
    expect(wrapper.find('[data-testid="field-name"]').isVisible()).toBe(true);
    expect(valueOf(wrapper, 'field-name')).toBe('老王烧烤');
    expect(queryMyShops).toHaveBeenCalledTimes(1); // 失败不触发重拉
  });

  it('24. 编辑时 openHours 为 null → 表单显示空字符串（不显示 null）', async () => {
    queryMyShops.mockResolvedValue(okResult([makeShop({ openHours: null })]) as never);
    const wrapper = mountPage();
    await flushPromises();
    await click(wrapper, 'shop-edit');

    expect(valueOf(wrapper, 'field-openHours')).toBe('');
  });
});

describe('Merchant/ShopList.vue — 路由（Phase 4.6 Step 1）', () => {
  it('/merchant/shops 已注册，且 meta 与 /merchant/orders 一致', async () => {
    const { default: router } = await import('@/router');

    const shops = router.resolve('/merchant/shops');
    expect(shops.name).toBe('MerchantShops');
    expect(shops.meta.requiresAuth).toBe(true);
    expect(shops.meta.requiresMerchant).toBe(true);

    const orders = router.resolve('/merchant/orders');
    expect(orders.name).toBe('MerchantOrders');
    expect(orders.meta.requiresAuth).toBe(true);
    expect(orders.meta.requiresMerchant).toBe(true);
  });
});
