import { expect, request, test, type Page } from '@playwright/test';
import { E2E_API_BASE, E2E_PREFIX } from '../helpers/env';
import {
  cleanupMerchants,
  countE2eVoucherResidue,
  deleteSeckillStockByPhones,
  ensureMerchantRole,
  getRedisSeckillStock,
  getSeckillRow,
  getVoucherRow,
  loginUser,
  CONSUMER_PHONE,
  MERCHANT_A_PHONE,
  MERCHANT_B_PHONE,
} from '../helpers/merchant';

/**
 * Merchant Voucher 真实浏览器 E2E（Phase 4.7 Step 2）
 *
 * 真实链路：真实浏览器 → 真实前端（vite dev）→ 真实登录态 → 真实后端 → 真实 MySQL + 真实 Redis。
 * 不用 mock / page.route() / HAR / api intercept。
 *
 * 造数纪律（与 Phase 4.5/4.6 一致）：
 * - 成为商家必须直写一次 DB（`UPDATE tb_user SET role=1`，无角色提升接口）——
 *   这是本套件唯一的 DB 写入口；
 * - 店铺/券由真实 UI 或真实 API 创建，绝不 INSERT；
 * - 券名/店名带 `E2E-` + timestamp，测后按 phone 整体删除；
 * - 秒杀券创建会写 `seckill:stock:{id}`（Redis）——cleanup 必须连带删除该 key。
 *
 * 本套件**绝不**触发真实秒杀下单（seckill.lua / Stream / Pending List），只验证
 * 「秒杀券创建 → DB + Redis stock 初始化」链路。
 */

/** 登录 token 写入 localStorage，模拟已登录态后再进入目标页 */
async function loginInBrowser(page: Page, token: string): Promise<void> {
  await page.goto('/');
  await page.evaluate((t) => {
    localStorage.setItem('authorization', t);
    localStorage.setItem('token', t);
  }, token);
}

const isVouchersGet = (url: string, method: string): boolean => {
  try {
    return new URL(url).pathname === '/api/vouchers/my' && method === 'GET';
  } catch {
    return false;
  }
};
const isVoucherPost = (url: string, method: string): boolean => {
  try {
    return new URL(url).pathname === '/api/vouchers' && method === 'POST';
  } catch {
    return false;
  }
};
const isSeckillPost = (url: string, method: string): boolean => {
  try {
    return new URL(url).pathname === '/api/vouchers/seckill-vouchers' && method === 'POST';
  } catch {
    return false;
  }
};
const isVoucherPut = (url: string, method: string, voucherId: string): boolean => {
  try {
    return new URL(url).pathname === `/api/vouchers/${voucherId}` && method === 'PUT';
  } catch {
    return false;
  }
};

const fieldInput = (page: Page, testId: string) => page.getByTestId(testId).locator('input');
/** rules 是 textarea（type="textarea"），用 textarea 定位 */
const fieldArea = (page: Page, testId: string) => page.getByTestId(testId).locator('textarea');

/** 创建一家店铺（owner_id 由后端从登录态注入），返回新店 id（字符串原样） */
async function createShop(token: string, name: string): Promise<string> {
  const api = await request.newContext({
    baseURL: E2E_API_BASE,
    extraHTTPHeaders: { authorization: token },
  });
  try {
    const res = await api.post('/shops', {
      data: {
        name,
        city: '杭州',
        typeId: 1,
        images: '[]',
        address: `${E2E_PREFIX}addr`,
        x: 120.1,
        y: 30.2,
        sold: 0,
        comments: 0,
        score: 50,
      },
    });
    const body = (await res.json()) as { success?: boolean; data?: string | number };
    if (!body.success) throw new Error(`createShop 失败: ${JSON.stringify(body)}`);
    return String(body.data);
  } finally {
    await api.dispose();
  }
}

test.describe('Merchant Voucher 真实浏览器 E2E', () => {
  const TS = Date.now();
  const SHOP_A = `${E2E_PREFIX}Voucher-ShopA-${TS}`;
  const SHOP_B = `${E2E_PREFIX}Voucher-ShopB-${TS}`;
  const NORMAL_TITLE = `${E2E_PREFIX}券-普通-${TS}`;
  const SECKILL_TITLE = `${E2E_PREFIX}券-秒杀-${TS}`;
  const EDITED_TITLE = `${E2E_PREFIX}券-秒杀-改-${TS}`;
  const B_TITLE = `${E2E_PREFIX}券-B-${TS}`;
  const SECKILL_BEGIN = '2026-09-25 10:00:00';
  const SECKILL_END = '2026-09-25 18:00:00';

  let merchantAToken = '';
  let merchantBToken = '';
  let consumerToken = '';
  let shopA = '';
  let shopB = '';
  let bVoucherId = '';
  let normalVoucherId = '';
  let seckillVoucherId = '';

  test.beforeAll(async () => {
    // 兜底：清掉上次中断可能残留的造数（含 Redis seckill:stock）
    deleteSeckillStockByPhones([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    await cleanupMerchants([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    const before = countE2eVoucherResidue([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    expect(before).toMatchObject({ vouchers: 0, seckillVouchers: 0, shops: 0, users: 0, orders: 0 });

    consumerToken = (await loginUser(CONSUMER_PHONE)).token;
    merchantAToken = (await ensureMerchantRole(MERCHANT_A_PHONE)).token;
    merchantBToken = (await ensureMerchantRole(MERCHANT_B_PHONE)).token;
    shopA = await createShop(merchantAToken, SHOP_A);
    shopB = await createShop(merchantBToken, SHOP_B);

    // Merchant B 建一张自己的券（归属 shopB），供「隔离」用例断言
    const apiB = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantBToken },
    });
    try {
      const res = await apiB.post('/vouchers', {
        data: { shopId: shopB, title: B_TITLE, payValue: 1500, actualValue: 3000, type: 0 },
      });
      const body = (await res.json()) as { success?: boolean; data?: string | number };
      expect(body.success).toBe(true);
      bVoucherId = String(body.data);
    } finally {
      await apiB.dispose();
    }
    expect(bVoucherId).toBeTruthy();
  });

  test.afterAll(async () => {
    // 顺序：先清 Redis 秒杀 key（此时券仍存在，可按商店家的券 id 映射），再删 DB
    deleteSeckillStockByPhones([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    await cleanupMerchants([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    // 硬证据：E2E 残留归零 + 本次秒杀券的 Redis key 已清
    const residue = countE2eVoucherResidue([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    expect(residue).toMatchObject({
      vouchers: 0,
      seckillVouchers: 0,
      shops: 0,
      users: 0,
      orders: 0,
    });
    if (seckillVoucherId) expect(getRedisSeckillStock(seckillVoucherId)).toBeNull();
  });

  test('1. 入口：Mine「商家优惠券」→ /merchant/vouchers 加载并真实调用 GET /vouchers/my（空态）', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    await page.goto('/mine');

    await expect(page.getByText('商家优惠券', { exact: true })).toBeVisible();
    await expect(page.getByText('商家管理', { exact: true })).toBeVisible();
    await expect(page.getByText('商家店铺', { exact: true })).toBeVisible();

    const listResponse = page.waitForResponse((r) => isVouchersGet(r.url(), r.request().method()), {
      timeout: 20_000,
    });
    await page.getByText('商家优惠券', { exact: true }).click();
    await expect(page).toHaveURL(/\/merchant\/vouchers/);

    const resp = await listResponse;
    expect(resp.status()).toBe(200);
    expect((await resp.json()).success).toBe(true);
    expect((await resp.json()).total).toBeNull(); // 不分页

    await expect(page.getByTestId('voucher-create')).toBeVisible();
    await expect(page.getByText('暂无优惠券')).toBeVisible(); // 新商家尚无券
  });

  test('2. 新建普通券：UI → POST /vouchers → DB type=0 / 金额分 / status=1 / 列表出现', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const firstList = page.waitForResponse((r) => isVouchersGet(r.url(), r.request().method()));
    await page.goto('/merchant/vouchers');
    await firstList;

    await page.getByTestId('voucher-create').click();
    await expect(page.getByRole('heading', { name: '新增优惠券' })).toBeVisible();

    // 默认「普通券」，先选一个店铺（A 名下 1 家）
    await page.locator('[data-testid="shop-option"]').first().click();
    await fieldInput(page, 'field-title').fill(NORMAL_TITLE);
    await fieldInput(page, 'field-subTitle').fill('周一至周日可用');
    await fieldArea(page, 'field-rules').fill('全场通用\\n不兑现、不找零');
    await fieldInput(page, 'field-payYuan').fill('10.50'); // 元
    await fieldInput(page, 'field-actualYuan').fill('20.00'); // 元
    // 普通券：秒杀字段不应出现
    await expect(page.getByTestId('field-stock')).toHaveCount(0);

    const postResponse = page.waitForResponse((r) => isVoucherPost(r.url(), r.request().method()));
    await page.getByTestId('voucher-save').click();

    const post = await postResponse;
    expect(post.status()).toBe(200);
    const body = (await post.json()) as { success?: boolean; data?: string | number };
    expect(body.success).toBe(true);
    normalVoucherId = String(body.data);
    expect(normalVoucherId).toBeTruthy();

    // 列表真实刷新出现新券
    await expect(
      page.getByTestId('voucher-card').filter({ hasText: NORMAL_TITLE })
    ).toBeVisible();

    // DB：type=0、金额分、status=1、归属 A 的店
    const row = getVoucherRow(normalVoucherId);
    expect(row.type).toBe(0);
    expect(row.payValue).toBe(1050); // 10.50 元 → 1050 分
    expect(row.actualValue).toBe(2000); // 20.00 元 → 2000 分
    expect(row.status).toBe(1);
    expect(row.title).toBe(NORMAL_TITLE);
    expect(row.shopId).toBe(shopA);
  });

  test('3. 新建秒杀券：UI → POST /vouchers/seckill-vouchers → DB 秒杀行 + Redis seckill:stock', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const firstList = page.waitForResponse((r) => isVouchersGet(r.url(), r.request().method()));
    await page.goto('/merchant/vouchers');
    await firstList;

    await page.getByTestId('voucher-create').click();
    // 切到秒杀券 → 出现秒杀字段
    await page.getByTestId('type-option').filter({ hasText: '秒杀券' }).click();
    await expect(page.getByTestId('field-stock')).toBeVisible();

    await page.locator('[data-testid="shop-option"]').first().click();
    await fieldInput(page, 'field-title').fill(SECKILL_TITLE);
    await fieldInput(page, 'field-payYuan').fill('10.00');
    await fieldInput(page, 'field-actualYuan').fill('20.00');
    await fieldInput(page, 'field-stock').fill('100');
    await fieldInput(page, 'field-beginTime').fill(SECKILL_BEGIN); // 空格格式 → 提交 T
    await fieldInput(page, 'field-endTime').fill(SECKILL_END);

    const postResponse = page.waitForResponse((r) =>
      isSeckillPost(r.url(), r.request().method())
    );
    await page.getByTestId('voucher-save').click();

    const post = await postResponse;
    expect(post.status()).toBe(200);
    const body = (await post.json()) as { success?: boolean; data?: string | number };
    expect(body.success).toBe(true);
    seckillVoucherId = String(body.data);
    expect(seckillVoucherId).toBeTruthy();

    await expect(
      page.getByTestId('voucher-card').filter({ hasText: SECKILL_TITLE })
    ).toContainText('库存 100');

    // DB：tb_voucher type=1 + tb_seckill_voucher 行
    const v = getVoucherRow(seckillVoucherId);
    expect(v.type).toBe(1);
    expect(v.payValue).toBe(1000);
    expect(v.actualValue).toBe(2000);
    expect(v.shopId).toBe(shopA);
    const sv = getSeckillRow(seckillVoucherId);
    expect(sv.stock).toBe(100);
    expect(sv.beginTime).toBe(SECKILL_BEGIN);
    expect(sv.endTime).toBe(SECKILL_END);

    // Redis：seckill:stock:{id} 与 DB stock 一致 —— 创建链路闭环成立
    expect(getRedisSeckillStock(seckillVoucherId)).toBe('100');
  });

  test('4. 编辑秒杀券：白名单 payload → DB 金额/标题改变，shop/type/stock/时间不变', async ({
    page,
  }) => {
    expect(seckillVoucherId).toBeTruthy();
    await loginInBrowser(page, merchantAToken);
    const firstList = page.waitForResponse((r) => isVouchersGet(r.url(), r.request().method()));
    await page.goto('/merchant/vouchers');
    await firstList;

    const card = page.getByTestId('voucher-card').filter({ hasText: SECKILL_TITLE });
    await card.getByTestId('voucher-edit').click();
    await expect(page.getByRole('heading', { name: '编辑优惠券' })).toBeVisible();

    // 编辑白名单：shopId / type / stock / 时间 都不出现（即使原券是秒杀券）
    await expect(page.getByTestId('shop-option')).toHaveCount(0);
    await expect(page.getByTestId('type-option')).toHaveCount(0);
    await expect(page.getByTestId('field-stock')).toHaveCount(0);
    await expect(page.getByTestId('field-beginTime')).toHaveCount(0);
    await expect(page.getByTestId('field-endTime')).toHaveCount(0);

    // 回填（金额分→元）+ 修改
    await expect(fieldInput(page, 'field-payYuan')).toHaveValue('10'); // 1000 分
    await expect(fieldInput(page, 'field-actualYuan')).toHaveValue('20'); // 2000 分
    await fieldInput(page, 'field-title').fill(EDITED_TITLE);
    await fieldInput(page, 'field-subTitle').fill('老客专属');
    await fieldInput(page, 'field-payYuan').fill('12.00');
    await fieldInput(page, 'field-actualYuan').fill('25.00');

    const putResponse = page.waitForResponse((r) =>
      isVoucherPut(r.url(), r.request().method(), seckillVoucherId)
    );
    await page.getByTestId('voucher-save').click();
    const put = await putResponse;
    expect(put.status()).toBe(200);
    expect((await put.json()).success).toBe(true);

    const v = getVoucherRow(seckillVoucherId);
    expect(v.title).toBe(EDITED_TITLE);
    expect(v.subTitle).toBe('老客专属');
    expect(v.payValue).toBe(1200);
    expect(v.actualValue).toBe(2500);
    // 归属/类型/秒杀字段一律不变（update whitelist 生效）
    expect(v.type).toBe(1);
    expect(v.shopId).toBe(shopA);
    const sv = getSeckillRow(seckillVoucherId);
    expect(sv.stock).toBe(100);
    expect(sv.beginTime).toBe(SECKILL_BEGIN);
    expect(sv.endTime).toBe(SECKILL_END);
  });

  test('5. 上架/下架：UI → PUT → DB status 2→1，reload 仍持久', async ({ page }) => {
    expect(normalVoucherId).toBeTruthy();
    await loginInBrowser(page, merchantAToken);
    const firstList = page.waitForResponse((r) => isVouchersGet(r.url(), r.request().method()));
    await page.goto('/merchant/vouchers');
    await firstList;

    const card = page.getByTestId('voucher-card').filter({ hasText: NORMAL_TITLE });

    // status=1 → 下架
    const put1 = page.waitForResponse((r) =>
      isVoucherPut(r.url(), r.request().method(), normalVoucherId)
    );
    await card.getByTestId('voucher-status-toggle').click();
    await put1;
    expect(getVoucherRow(normalVoucherId).status).toBe(2);

    // reload 后仍显示已下架 + 按钮变「上架」
    await page.reload();
    await expect(page.getByTestId('voucher-card').filter({ hasText: NORMAL_TITLE })).toBeVisible();
    await expect(
      page.getByTestId('voucher-card').filter({ hasText: NORMAL_TITLE }).getByTestId('voucher-status-toggle')
    ).toContainText('上架');
    expect(getVoucherRow(normalVoucherId).status).toBe(2);

    // 下架 → 上架
    const put2 = page.waitForResponse((r) =>
      isVoucherPut(r.url(), r.request().method(), normalVoucherId)
    );
    await page
      .getByTestId('voucher-card')
      .filter({ hasText: NORMAL_TITLE })
      .getByTestId('voucher-status-toggle')
      .click();
    await put2;
    expect(getVoucherRow(normalVoucherId).status).toBe(1);

    await page.reload();
    await expect(
      page.getByTestId('voucher-card').filter({ hasText: NORMAL_TITLE }).getByTestId('voucher-status-toggle')
    ).toContainText('下架');
  });

  test('6. 权限 role=0：无入口 + 前端拦截回 /mine + 后端 GET /vouchers/my 403/40003', async ({
    page,
  }) => {
    await loginInBrowser(page, consumerToken);
    await page.goto('/mine');
    await expect(page.getByText('商家优惠券', { exact: true })).toHaveCount(0);

    await page.goto('/merchant/vouchers');
    await expect(page).toHaveURL(/\/mine/);

    // 后端仍是最终安全边界
    const api = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: consumerToken },
    });
    try {
      const res = await api.get('/vouchers/my');
      expect(res.status()).toBe(403);
      expect((await res.json()).errorCode).toBe('40003');
    } finally {
      await api.dispose();
    }
  });

  test('7. 匿名：访问 /merchant/vouchers 进入登录流程；GET /vouchers/my → 401', async ({
    page,
  }) => {
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.goto('/merchant/vouchers');
    await expect(page).toHaveURL(/\/login/);

    const api = await request.newContext({ baseURL: E2E_API_BASE });
    try {
      const res = await api.get('/vouchers/my');
      expect(res.status()).toBe(401);
      expect((await res.json()).errorCode).toBe('10001');
    } finally {
      await api.dispose();
    }
  });

  test('8. 跨商家隔离（B 视角）：Browser 只见自己的券，不见 A 的券', async ({ page }) => {
    expect(merchantBToken).toBeTruthy();
    expect(bVoucherId).toBeTruthy();

    await loginInBrowser(page, merchantBToken);
    const bList = page.waitForResponse((r) => isVouchersGet(r.url(), r.request().method()));
    await page.goto('/merchant/vouchers');
    await bList;

    await expect(page.getByTestId('voucher-card').filter({ hasText: B_TITLE })).toBeVisible();
    await expect(page.getByTestId('voucher-card').filter({ hasText: NORMAL_TITLE })).toHaveCount(0);
    await expect(page.getByTestId('voucher-card').filter({ hasText: EDITED_TITLE })).toHaveCount(0);
  });

  test('9. 跨商家隔离（A 视角）：只见自己的券；B 直改 A 的券 → 403/40004，DB 不动', async ({
    page,
  }) => {
    expect(merchantAToken).toBeTruthy();
    expect(merchantBToken).toBeTruthy();
    expect(normalVoucherId).toBeTruthy();

    // A 在浏览器里只见自己的券，不见 B 的券（每个用例独立 browser context，避免 token 串台）
    await loginInBrowser(page, merchantAToken);
    const aList = page.waitForResponse((r) => isVouchersGet(r.url(), r.request().method()));
    await page.goto('/merchant/vouchers');
    await aList;
    await expect(page.getByTestId('voucher-card').filter({ hasText: B_TITLE })).toHaveCount(0);
    await expect(page.getByTestId('voucher-card').filter({ hasText: NORMAL_TITLE })).toBeVisible();
    await expect(page.getByTestId('voucher-card').filter({ hasText: EDITED_TITLE })).toBeVisible();

    // B 直改 A 的券 → 403/40004，DB 不动
    const before = getVoucherRow(normalVoucherId);
    const apiB2 = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantBToken },
    });
    try {
      const res = await apiB2.put(`/vouchers/${normalVoucherId}`, {
        data: { title: `E2E-HIJACK-${TS}`, payValue: 1, actualValue: 1, status: 1 },
      });
      expect(res.status()).toBe(403);
      expect((await res.json()).errorCode).toBe('40004');
    } finally {
      await apiB2.dispose();
    }
    expect(getVoucherRow(normalVoucherId)).toEqual(before);
  });
});