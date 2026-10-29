import { expect, request, test, type Page } from '@playwright/test';
import { E2E_API_BASE, E2E_PREFIX } from '../helpers/env';
import {
  cleanupMerchants,
  countE2eFullResidue,
  countE2eVoucherResidue,
  countMerchantOrdersByPhones,
  countShopsByPhones,
  countVouchersByPhones,
  createPaidOrder,
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
 * Merchant Dashboard 真实浏览器 E2E（Phase 4.8 Step 2）
 *
 * 真实链路：真实浏览器（Chrome）→ 真实前端（vite dev）→ 真实登录态 →
 * 真实后端（Spring Boot）→ 真实 MySQL + 真实 Redis。
 * 不用 mock / page.route() / HAR / api intercept / fixture 替代后端。
 *
 * 数据设计（全部走真实 API 创建，唯一 DB 写是 becoming-merchant 的 role=1 提升，
 * 与 Phase 4.5/4.6/4.7 一致）：
 * - Merchant A：2 店 / 11 券（10 上架含 1 秒杀 type=1，1 下架）/
 *   8 订单（6×status=2 待核销，2×status=3 已核销）
 * - Merchant B：1 店 / 1 券 / 1 订单（隔离对照）
 * - Merchant C：role=1 空商家（空状态）
 * - 消费者：种子用户（role=0，权限对照）
 *
 * 本套件绝不触发真实秒杀下单（seckill.lua / Stream / PendingList）：
 * 只创建秒杀券（验证 type=1 计入「秒杀」），订单一律走普通券 purchase-orders。
 * Dashboard 本身是只读页：5 个 useQuery 全是 GET，不写 Redis、不改 stock。
 */

const MERCHANT_C_PHONE = process.env.E2E_MERCHANT_C_PHONE ?? '13900000003';

const isShopsMy = (url: string, method: string): boolean => {
  try {
    return new URL(url).pathname === '/api/shops/my' && method === 'GET';
  } catch {
    return false;
  }
};
const isVouchersMy = (url: string, method: string): boolean => {
  try {
    return new URL(url).pathname === '/api/vouchers/my' && method === 'GET';
  } catch {
    return false;
  }
};
const isMerchantOrders = (url: string, method: string): boolean => {
  try {
    return new URL(url).pathname === '/api/voucher-orders/merchant' && method === 'GET';
  } catch {
    return false;
  }
};

/** 把登录 token 注入 localStorage，模拟「已登录」后再进入目标页 */
async function loginInBrowser(page: Page, token: string): Promise<void> {
  await page.goto('/');
  await page.evaluate((t) => {
    localStorage.setItem('authorization', t);
    localStorage.setItem('token', t);
  }, token);
}

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
        address: `${E2E_PREFIX}addr-dash`,
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

async function createNormalVoucher(token: string, shopId: string, title: string): Promise<string> {
  const api = await request.newContext({
    baseURL: E2E_API_BASE,
    extraHTTPHeaders: { authorization: token },
  });
  try {
    const res = await api.post('/vouchers', {
      data: { shopId, title, payValue: 1000, actualValue: 2000, type: 0 },
    });
    const body = (await res.json()) as { success?: boolean; data?: string | number };
    if (!body.success) throw new Error(`createNormalVoucher 失败: ${JSON.stringify(body)}`);
    return String(body.data);
  } finally {
    await api.dispose();
  }
}

async function createSeckillVoucher(
  token: string,
  shopId: string,
  title: string,
  stock: number
): Promise<string> {
  const api = await request.newContext({
    baseURL: E2E_API_BASE,
    extraHTTPHeaders: { authorization: token },
  });
  try {
    const res = await api.post('/vouchers/seckill-vouchers', {
      data: {
        shopId,
        title,
        payValue: 1000,
        actualValue: 2000,
        type: 1,
        stock,
        beginTime: '2026-09-25T10:00:00',
        endTime: '2026-09-25T18:00:00',
      },
    });
    const body = (await res.json()) as { success?: boolean; data?: string | number };
    if (!body.success) throw new Error(`createSeckillVoucher 失败: ${JSON.stringify(body)}`);
    return String(body.data);
  } finally {
    await api.dispose();
  }
}

/** 下架券：白名单更新需带 title（后端 @NotBlank） */
async function unpublishVoucher(token: string, voucherId: string, title: string): Promise<void> {
  const api = await request.newContext({
    baseURL: E2E_API_BASE,
    extraHTTPHeaders: { authorization: token },
  });
  try {
    const res = await api.put(`/vouchers/${voucherId}`, { data: { title, status: 2 } });
    const body = (await res.json()) as { success?: boolean };
    if (!body.success) throw new Error(`unpublishVoucher 失败: ${JSON.stringify(body)}`);
  } finally {
    await api.dispose();
  }
}

async function redeemOrder(merchantToken: string, orderId: string): Promise<void> {
  const api = await request.newContext({
    baseURL: E2E_API_BASE,
    extraHTTPHeaders: { authorization: merchantToken },
  });
  try {
    const res = await api.post(`/voucher-orders/${orderId}/redeem`);
    const body = (await res.json()) as { success?: boolean };
    if (!body.success) throw new Error(`redeem 失败 orderId=${orderId}: ${JSON.stringify(body)}`);
  } finally {
    await api.dispose();
  }
}

test.describe('Merchant Dashboard 真实浏览器 E2E', () => {
  const TS = Date.now();
  const SHOP_A1 = `${E2E_PREFIX}Dash-ShopA1-${TS}`;
  const SHOP_A2 = `${E2E_PREFIX}Dash-ShopA2-${TS}`;
  const SHOP_B = `${E2E_PREFIX}Dash-ShopB-${TS}`;
  const V_PUB = `${E2E_PREFIX}券-上架-${TS}`;
  const V_UNPUB = `${E2E_PREFIX}券-下架-${TS}`;
  const V_SECKILL = `${E2E_PREFIX}券-秒杀-${TS}`;
  const V_ORDER = `${E2E_PREFIX}券-订单-${TS}`;
  const V_B = `${E2E_PREFIX}券-B-${TS}`;

  // Merchant A：2 店 / 11 券（10 上架含 1 秒杀）/ 8 订单（6 待核销 + 2 已核销）
  const EXPECT_SHOPS_A = 2;
  const EXPECT_VOUCHERS_A = 11;
  const EXPECT_PUBLISHED_A = 10;
  const EXPECT_SECKILL_A = 1;
  const EXPECT_PENDING_A = 6;
  const EXPECT_REDEEMED_A = 2;
  const SECKILL_STOCK = 100;

  let merchantAToken = '';
  let merchantBToken = '';
  let merchantCToken = '';
  let consumerToken = '';
  let seckillVoucherId = '';
  let seckillStockBefore = '';
  let bOrderId = '';
  let bVoucherId = '';
  let totalShopsBefore = 0;

  test.beforeAll(async () => {
    deleteSeckillStockByPhones([MERCHANT_A_PHONE, MERCHANT_B_PHONE, MERCHANT_C_PHONE]);
    await cleanupMerchants([MERCHANT_A_PHONE, MERCHANT_B_PHONE, MERCHANT_C_PHONE]);
    const before = countE2eVoucherResidue([MERCHANT_A_PHONE, MERCHANT_B_PHONE, MERCHANT_C_PHONE]);
    expect(before).toMatchObject({ vouchers: 0, seckillVouchers: 0, shops: 0, users: 0, orders: 0 });
    totalShopsBefore = countE2eFullResidue([
      MERCHANT_A_PHONE,
      MERCHANT_B_PHONE,
      MERCHANT_C_PHONE,
    ]).totalShops;

    consumerToken = (await loginUser(CONSUMER_PHONE)).token;
    merchantAToken = (await ensureMerchantRole(MERCHANT_A_PHONE)).token;
    merchantBToken = (await ensureMerchantRole(MERCHANT_B_PHONE)).token;
    merchantCToken = (await ensureMerchantRole(MERCHANT_C_PHONE)).token;

    // ---- Merchant A：2 店 ----
    const shopA1 = await createShop(merchantAToken, SHOP_A1);
    const shopA2 = await createShop(merchantAToken, SHOP_A2);

    // ---- Merchant A：基础 3 券（上架 / 下架 / 秒杀最后建，保证最近券首位是秒杀券） ----
    const vPub = await createNormalVoucher(merchantAToken, shopA1, V_PUB);
    void vPub;
    const vUnpub = await createNormalVoucher(merchantAToken, shopA1, V_UNPUB);
    await unpublishVoucher(merchantAToken, vUnpub, V_UNPUB);
    expect(getVoucherRow(vUnpub).status).toBe(2);

    // ---- Merchant A：8 张订单券（VD1..VD8，普通上架） ----
    const orderVoucherIds: string[] = [];
    for (let i = 0; i < 8; i += 1) {
      const shopId = i % 2 === 0 ? shopA1 : shopA2;
      orderVoucherIds.push(await createNormalVoucher(merchantAToken, shopId, `${V_ORDER}-${i}`));
    }

    // ---- Merchant A：秒杀券（最后建 → /vouchers/my desc 首位） ----
    seckillVoucherId = await createSeckillVoucher(merchantAToken, shopA2, V_SECKILL, SECKILL_STOCK);
    expect(getVoucherRow(seckillVoucherId).type).toBe(1);
    expect(getSeckillRow(seckillVoucherId).stock).toBe(SECKILL_STOCK);
    expect(getRedisSeckillStock(seckillVoucherId)).toBe(String(SECKILL_STOCK));
    seckillStockBefore = String(SECKILL_STOCK);

    // ---- Merchant A：8 订单（6 待核销 + 2 已核销），种子消费者复购不同券 ----
    const paidIds: string[] = [];
    for (const vid of orderVoucherIds) {
      paidIds.push(await createPaidOrder(consumerToken, vid));
    }
    await redeemOrder(merchantAToken, paidIds[6]);
    await redeemOrder(merchantAToken, paidIds[7]);

    // ---- Merchant B：隔离对照（1 店 / 1 券 / 1 待核销订单） ----
    const shopB = await createShop(merchantBToken, SHOP_B);
    bVoucherId = await createNormalVoucher(merchantBToken, shopB, V_B);
    bOrderId = await createPaidOrder(consumerToken, bVoucherId);

    // ---- 造数断言（DB 直接口径，后续 UI/API 均与之对齐） ----
    expect(countShopsByPhones([MERCHANT_A_PHONE])).toBe(EXPECT_SHOPS_A);
    expect(countVouchersByPhones([MERCHANT_A_PHONE])).toEqual({
      total: EXPECT_VOUCHERS_A,
      published: EXPECT_PUBLISHED_A,
      seckill: EXPECT_SECKILL_A,
    });
    expect(countMerchantOrdersByPhones([MERCHANT_A_PHONE], 2)).toBe(EXPECT_PENDING_A);
    expect(countMerchantOrdersByPhones([MERCHANT_A_PHONE], 3)).toBe(EXPECT_REDEEMED_A);
  });

  test.afterAll(async () => {
    deleteSeckillStockByPhones([MERCHANT_A_PHONE, MERCHANT_B_PHONE, MERCHANT_C_PHONE]);
    await cleanupMerchants([MERCHANT_A_PHONE, MERCHANT_B_PHONE, MERCHANT_C_PHONE]);
    const residue = countE2eVoucherResidue([MERCHANT_A_PHONE, MERCHANT_B_PHONE, MERCHANT_C_PHONE]);
    expect(residue).toMatchObject({
      vouchers: 0,
      seckillVouchers: 0,
      shops: 0,
      users: 0,
      orders: 0,
    });
    // 种子店铺总数与跑前一致（未误删种子数据）
    expect(
      countE2eFullResidue([MERCHANT_A_PHONE, MERCHANT_B_PHONE, MERCHANT_C_PHONE]).totalShops
    ).toBe(totalShopsBefore);
    if (seckillVoucherId) expect(getRedisSeckillStock(seckillVoucherId)).toBeNull();
  });

  test('1. 入口：Merchant 登录 → Mine「经营概览」→ /merchant/dashboard，四区 + 最近段真实渲染', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    await page.goto('/mine');
    // Mine 落定后再点入口：统计面板加载完成证明 refreshUserInfo/fetchDashboard 均已回包，
    // 避免冷启动首测时点击早于页面 settle 导致的偶发无导航（flake 加固，非功能改动）
    await expect(page.getByText('连续签到')).toBeVisible();
    await expect(page.getByText('经营概览', { exact: true })).toBeVisible();

    const shopsResp = page.waitForResponse((r) => isShopsMy(r.url(), r.request().method()), {
      timeout: 20_000,
    });
    const vouchersResp = page.waitForResponse((r) => isVouchersMy(r.url(), r.request().method()), {
      timeout: 20_000,
    });
    await page.getByText('经营概览', { exact: true }).click();
    await expect(page).toHaveURL(/\/merchant\/dashboard/);

    // 真实 API 均 200 + success（非 mock：waitForResponse 捕获真实后端响应）
    expect((await (await shopsResp).json()).success).toBe(true);
    expect((await (await vouchersResp).json()).success).toBe(true);

    await expect(page.getByText('经营概览', { exact: true }).first()).toBeVisible();
    await expect(page.getByTestId('stat-shops')).toContainText('我的店铺');
    await expect(page.getByTestId('stat-vouchers')).toContainText('优惠券');
    await expect(page.getByTestId('stat-pending')).toContainText('待核销订单');
    await expect(page.getByTestId('stat-redeemed')).toContainText('已核销订单');
    await expect(page.getByText('最近订单', { exact: true })).toBeVisible();
    await expect(page.getByText('最近优惠券', { exact: true })).toBeVisible();
  });

  test('2. 店铺数：UI == API /shops/my == DB owner shop count（=2）', async ({ page }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isShopsMy(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;

    await expect(page.getByTestId('stat-shops-value')).toHaveText(String(EXPECT_SHOPS_A));

    const api = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantAToken },
    });
    try {
      const res = await api.get('/shops/my');
      const body = (await res.json()) as { success?: boolean; data?: Array<unknown> };
      expect(body.success).toBe(true);
      expect((body.data ?? []).length).toBe(EXPECT_SHOPS_A);
    } finally {
      await api.dispose();
    }
    expect(countShopsByPhones([MERCHANT_A_PHONE])).toBe(EXPECT_SHOPS_A);
  });

  test('3. 优惠券统计：总数/上架/秒杀 UI == API 数组口径 == DB；type=1 计入秒杀', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isVouchersMy(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;

    await expect(page.getByTestId('stat-vouchers-value')).toHaveText(String(EXPECT_VOUCHERS_A));
    await expect(page.getByTestId('stat-vouchers')).toContainText(`上架 ${EXPECT_PUBLISHED_A}`);
    await expect(page.getByTestId('stat-vouchers')).toContainText(`秒杀 ${EXPECT_SECKILL_A}`);

    // DB 行级：秒杀券 type=1（非标题判断）
    expect(getVoucherRow(seckillVoucherId).type).toBe(1);

    const api = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantAToken },
    });
    try {
      const res = await api.get('/vouchers/my');
      const body = (await res.json()) as {
        success?: boolean;
        data?: Array<{ status?: number; type?: number }>;
      };
      const list = body.data ?? [];
      expect(list.length).toBe(EXPECT_VOUCHERS_A);
      expect(list.filter((v) => v.status === 1).length).toBe(EXPECT_PUBLISHED_A);
      expect(list.filter((v) => v.type === 1).length).toBe(EXPECT_SECKILL_A);
    } finally {
      await api.dispose();
    }
    expect(countVouchersByPhones([MERCHANT_A_PHONE])).toEqual({
      total: EXPECT_VOUCHERS_A,
      published: EXPECT_PUBLISHED_A,
      seckill: EXPECT_SECKILL_A,
    });
  });

  test('4. 待核销 total：真实订单 6 > 请求 size=1；UI 用 total 而非页长', async ({ page }) => {
    await loginInBrowser(page, merchantAToken);

    const pendingResp = page.waitForResponse(
      (r) => {
        try {
          const u = new URL(r.url());
          return (
            u.pathname === '/api/voucher-orders/merchant' &&
            r.request().method() === 'GET' &&
            u.searchParams.get('status') === '2'
          );
        } catch {
          return false;
        }
      },
      { timeout: 20_000 }
    );
    await page.goto('/merchant/dashboard');
    const resp = await pendingResp;
    const body = (await resp.json()) as { success?: boolean; data?: Array<unknown>; total?: number };
    // 关键证据：后端返回页长 1（size=1）但 total=6
    expect(body.success).toBe(true);
    expect((body.data ?? []).length).toBeLessThanOrEqual(1);
    expect(Number(body.total)).toBe(EXPECT_PENDING_A);

    await expect(page.getByTestId('stat-pending-value')).toHaveText(String(EXPECT_PENDING_A));

    // API 直查 + DB 交叉
    const api = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantAToken },
    });
    try {
      const res = await api.get('/voucher-orders/merchant?current=1&size=1&status=2');
      expect(Number((await res.json()).total)).toBe(EXPECT_PENDING_A);
    } finally {
      await api.dispose();
    }
    expect(countMerchantOrdersByPhones([MERCHANT_A_PHONE], 2)).toBe(EXPECT_PENDING_A);
  });

  test('5. 已核销 total：UI == GET status=3 total == DB count（=2）', async ({ page }) => {
    await loginInBrowser(page, merchantAToken);
    const redeemedResp = page.waitForResponse(
      (r) => {
        try {
          const u = new URL(r.url());
          return (
            u.pathname === '/api/voucher-orders/merchant' &&
            r.request().method() === 'GET' &&
            u.searchParams.get('status') === '3'
          );
        } catch {
          return false;
        }
      },
      { timeout: 20_000 }
    );
    await page.goto('/merchant/dashboard');
    const resp = await redeemedResp;
    expect(Number((await resp.json()).total)).toBe(EXPECT_REDEEMED_A);

    await expect(page.getByTestId('stat-redeemed-value')).toHaveText(String(EXPECT_REDEEMED_A));

    const api = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantAToken },
    });
    try {
      const res = await api.get('/voucher-orders/merchant?current=1&size=1&status=3');
      expect(Number((await res.json()).total)).toBe(EXPECT_REDEEMED_A);
    } finally {
      await api.dispose();
    }
    expect(countMerchantOrdersByPhones([MERCHANT_A_PHONE], 3)).toBe(EXPECT_REDEEMED_A);
  });

  test('6. 最近订单：UI 前 5 与 API current=1&size=5 一致（标题/状态/金额/时间），金额仅核对字段', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isMerchantOrders(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;

    const fetchRecentOrders = async (): Promise<{
      list: Array<{
        voucherTitle?: string | null;
        status?: number;
        payValue?: number | string | null;
        createTime?: string | null;
      }>;
      total: number;
    }> => {
      const api = await request.newContext({
        baseURL: E2E_API_BASE,
        extraHTTPHeaders: { authorization: merchantAToken },
      });
      try {
        const res = await api.get('/voucher-orders/merchant?current=1&size=5');
        const body = (await res.json()) as {
          success?: boolean;
          data?: Array<{
            voucherTitle?: string | null;
            status?: number;
            payValue?: number | string | null;
            createTime?: string | null;
          }>;
          total?: number;
        };
        return { list: body.data ?? [], total: Number(body.total ?? 0) };
      } finally {
        await api.dispose();
      }
    };
    const { list: apiList, total: apiTotal } = await fetchRecentOrders();
    expect(apiTotal).toBe(EXPECT_PENDING_A + EXPECT_REDEEMED_A);
    expect(apiList.length).toBe(5);

    const cards = page.getByTestId('recent-order');
    await expect(cards).toHaveCount(5);
    // 首卡与 API 首条对齐（最新订单）；状态文案与金额格式与 API 字段一致
    const firstApi = apiList[0];
    const firstCard = cards.first();
    await expect(firstCard).toContainText(String(firstApi.voucherTitle ?? ''));
    await expect(firstCard).toContainText(firstApi.status === 3 ? '已核销' : '已支付');
    await expect(firstCard).toContainText('¥');
    // 金额展示与 API payValue（分→元）一致，不解读为收入
    const yuan = Number(firstApi.payValue ?? 0) / 100;
    const yuanText = Number.isInteger(yuan) ? String(yuan) : yuan.toFixed(2);
    await expect(firstCard).toContainText(yuanText);
    if (firstApi.createTime) {
      await expect(firstCard).toContainText(firstApi.createTime.slice(0, 10));
    }
  });

  test('7. 最近优惠券：UI 前 5 与 API /vouchers/my 一致；秒杀券展示类型+状态+库存（API 源，非 Redis）', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isVouchersMy(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;

    const fetchMyVouchers = async (): Promise<
      Array<{ title?: string; type?: number; status?: number; stock?: number | null }>
    > => {
      const api = await request.newContext({
        baseURL: E2E_API_BASE,
        extraHTTPHeaders: { authorization: merchantAToken },
      });
      try {
        const res = await api.get('/vouchers/my');
        const body = (await res.json()) as {
          data?: Array<{ title?: string; type?: number; status?: number; stock?: number | null }>;
        };
        return body.data ?? [];
      } finally {
        await api.dispose();
      }
    };
    const apiList = await fetchMyVouchers();
    expect(apiList.length).toBe(EXPECT_VOUCHERS_A);

    const cards = page.getByTestId('recent-voucher');
    await expect(cards).toHaveCount(5);
    // UI 标题序列 == API 前 5（Dashboard 用 slice(0,5)，不另读 Redis）
    for (let i = 0; i < 5; i += 1) {
      await expect(cards.nth(i)).toContainText(String(apiList[i].title ?? ''));
    }
    // 秒杀券（最新）在首位：类型 + 库存来自 API 回填（与 DB 一致）
    await expect(cards.first()).toContainText(V_SECKILL);
    await expect(cards.first()).toContainText('秒杀券');
    await expect(cards.first()).toContainText('上架');
    await expect(cards.first()).toContainText(`库存 ${SECKILL_STOCK}`);
  });

  test('8. 空状态：新商家无店/券/单 → 0 + 暂无文案，无 NaN/白屏', async ({ page }) => {
    await loginInBrowser(page, merchantCToken);
    const first = page.waitForResponse((r) => isShopsMy(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;

    await expect(page.getByTestId('stat-shops-value')).toHaveText('0');
    await expect(page.getByTestId('stat-vouchers-value')).toHaveText('0');
    await expect(page.getByTestId('stat-pending-value')).toHaveText('0');
    await expect(page.getByTestId('stat-redeemed-value')).toHaveText('0');
    await expect(page.getByText('暂无优惠券', { exact: true })).toBeVisible();
    await expect(page.getByText('暂无订单', { exact: true })).toBeVisible();
    const bodyText = (await page.locator('body').innerText()) ?? '';
    expect(bodyText).not.toContain('NaN');
    expect(bodyText).not.toContain('undefined');
  });

  test('9. 权限：role=0 无入口且被前端拦截；后端三接口 403/40003（不新增权限逻辑）', async ({
    page,
  }) => {
    await loginInBrowser(page, consumerToken);
    await page.goto('/mine');
    await expect(page.getByText('经营概览', { exact: true })).toHaveCount(0);

    await page.goto('/merchant/dashboard');
    await expect(page).toHaveURL(/\/mine/);

    const api = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: consumerToken },
    });
    try {
      const shopsRes = await api.get('/shops/my');
      expect(shopsRes.status()).toBe(403);
      expect((await shopsRes.json()).errorCode).toBe('40003');

      const vouchersRes = await api.get('/vouchers/my');
      expect(vouchersRes.status()).toBe(403);
      expect((await vouchersRes.json()).errorCode).toBe('40003');

      const ordersRes = await api.get('/voucher-orders/merchant?current=1&size=10');
      expect(ordersRes.status()).toBe(403);
      expect((await ordersRes.json()).errorCode).toBe('40003');
    } finally {
      await api.dispose();
    }
  });

  test('10. 匿名：无登录态访问被 redirect 到登录；真实 API 保持 401/10001', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.goto('/merchant/dashboard');
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

  test('11. 隔离 + 三层一致 + Redis 未动：A 看不见 B；UI==API total==DB；seckill stock 不变', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isShopsMy(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;
    await expect(page.getByTestId('stat-shops-value')).toHaveText(String(EXPECT_SHOPS_A));

    // B 的数据真实存在（对照非「B 无数据」），但 A 的 Dashboard 不可见
    expect(bVoucherId).toBeTruthy();
    expect(bOrderId).toBeTruthy();
    const pageText = (await page.locator('body').innerText()) ?? '';
    expect(pageText).not.toContain(SHOP_B);
    expect(pageText).not.toContain(V_B);
    expect(pageText).not.toContain(bOrderId);

    const apiB = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantBToken },
    });
    try {
      const res = await apiB.get('/voucher-orders/merchant?current=1&size=10');
      const body = (await res.json()) as { data?: Array<{ orderId?: unknown }> };
      expect((body.data ?? []).map((i) => String(i.orderId))).toContain(bOrderId);
    } finally {
      await apiB.dispose();
    }

    // 三层一致（DB → API total → UI）：四组口径一次收敛
    const apiA = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantAToken },
    });
    try {
      const shops = (await (await apiA.get('/shops/my')).json()) as { data?: Array<unknown> };
      const vouchers = (await (await apiA.get('/vouchers/my')).json()) as {
        data?: Array<unknown>;
      };
      const pending = (await (
        await apiA.get('/voucher-orders/merchant?current=1&size=1&status=2')
      ).json()) as { total?: number };
      const redeemed = (await (
        await apiA.get('/voucher-orders/merchant?current=1&size=1&status=3')
      ).json()) as { total?: number };
      expect((shops.data ?? []).length).toBe(countShopsByPhones([MERCHANT_A_PHONE]));
      expect((vouchers.data ?? []).length).toBe(countVouchersByPhones([MERCHANT_A_PHONE]).total);
      expect(Number(pending.total)).toBe(countMerchantOrdersByPhones([MERCHANT_A_PHONE], 2));
      expect(Number(redeemed.total)).toBe(countMerchantOrdersByPhones([MERCHANT_A_PHONE], 3));
    } finally {
      await apiA.dispose();
    }
    await expect(page.getByTestId('stat-vouchers-value')).toHaveText(String(EXPECT_VOUCHERS_A));
    await expect(page.getByTestId('stat-pending-value')).toHaveText(String(EXPECT_PENDING_A));
    await expect(page.getByTestId('stat-redeemed-value')).toHaveText(String(EXPECT_REDEEMED_A));

    // Redis：Dashboard 全程只读，seckill stock 纹丝不动
    expect(getRedisSeckillStock(seckillVoucherId)).toBe(seckillStockBefore);
    expect(getSeckillRow(seckillVoucherId).stock).toBe(SECKILL_STOCK);
  });

  // ------------------------------------------------------------------
  // Phase 4.9 Step 2：Dashboard 下钻导航真实 E2E（黑盒，只观察真实响应）
  // ------------------------------------------------------------------

  /** OrderList 当前选中的 tab 下标（van-tab--active），-1 表示未找到 */
  async function activeTabIndex(page: Page): Promise<number> {
    const tabs = page.locator('.van-tab');
    const count = await tabs.count();
    for (let i = 0; i < count; i += 1) {
      const cls = (await tabs.nth(i).getAttribute('class')) ?? '';
      if (cls.includes('van-tab--active')) return i;
    }
    return -1;
  }

  /** 等待一条带指定 status 的真实商家订单 GET，并返回其 total */
  async function waitMerchantOrdersTotal(page: Page, status: string): Promise<number> {
    const resp = await page.waitForResponse(
      (r) => {
        try {
          const u = new URL(r.url());
          return (
            u.pathname === '/api/voucher-orders/merchant' &&
            r.request().method() === 'GET' &&
            u.searchParams.get('status') === status
          );
        } catch {
          return false;
        }
      },
      { timeout: 20_000 }
    );
    return Number((await resp.json()).total ?? -1);
  }

  test('D1. 下钻店铺：点击「我的店铺」→ /merchant/shops，店铺真实加载', async ({ page }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isShopsMy(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;

    const shopsResp = page.waitForResponse((r) => isShopsMy(r.url(), r.request().method()), {
      timeout: 20_000,
    });
    await page.getByTestId('stat-shops').click();
    await expect(page).toHaveURL(/\/merchant\/shops/);
    expect((await (await shopsResp).json()).success).toBe(true);

    // Shop 页真实渲染（标题 + 本次造数的店），非仅 URL 断言
    await expect(page.getByText('商家店铺', { exact: true }).first()).toBeVisible();
    await expect(
      page.getByTestId('merchant-shop-card').filter({ hasText: SHOP_A1 })
    ).toBeVisible();
  });

  test('D2. 下钻优惠券：点击「优惠券」→ /merchant/vouchers，券真实加载', async ({ page }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isVouchersMy(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;

    const vouchersResp = page.waitForResponse((r) => isVouchersMy(r.url(), r.request().method()), {
      timeout: 20_000,
    });
    await page.getByTestId('stat-vouchers').click();
    await expect(page).toHaveURL(/\/merchant\/vouchers/);
    expect((await (await vouchersResp).json()).success).toBe(true);

    await expect(page.getByTestId('voucher-create')).toBeVisible();
    await expect(page.getByTestId('voucher-card').filter({ hasText: V_SECKILL })).toBeVisible();
  });

  test('D3. 下钻待核销：点击卡片 → ?tab=pending，待核销选中且真实请求 status=2（total=6）', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isMerchantOrders(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;
    await expect(page.getByTestId('stat-pending-value')).toHaveText(String(EXPECT_PENDING_A));

    const totalPromise = waitMerchantOrdersTotal(page, '2');
    await page.getByTestId('stat-pending').click();
    await expect(page).toHaveURL(/\/merchant\/orders\?tab=pending/);

    // 真实 network：OrderList 预选待核销触发 status=2，total 与 Dashboard 一致
    expect(await totalPromise).toBe(EXPECT_PENDING_A);
    expect(await activeTabIndex(page)).toBe(1);
    await expect(page.getByText(`共 ${EXPECT_PENDING_A} 条`)).toBeVisible();
  });

  test('D4. 下钻已核销：点击卡片 → ?tab=completed，已核销选中且真实请求 status=3（total=2）', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isMerchantOrders(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;
    await expect(page.getByTestId('stat-redeemed-value')).toHaveText(String(EXPECT_REDEEMED_A));

    const totalPromise = waitMerchantOrdersTotal(page, '3');
    await page.getByTestId('stat-redeemed').click();
    await expect(page).toHaveURL(/\/merchant\/orders\?tab=completed/);

    expect(await totalPromise).toBe(EXPECT_REDEEMED_A);
    expect(await activeTabIndex(page)).toBe(2);
    await expect(page.getByText(`共 ${EXPECT_REDEEMED_A} 条`)).toBeVisible();
  });

  test('D5. 最近订单下钻：点击行 → /merchant/orders（默认「全部」），无 Order Detail', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isMerchantOrders(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;
    await expect(page.getByTestId('recent-order')).toHaveCount(5);

    await page.getByTestId('recent-order').first().click();
    await expect(page).toHaveURL(/\/merchant\/orders$/);
    // 先等列表落定（首屏挂载时 van-tab--active 有一帧延迟，属渲染竞态而非功能问题）
    await expect(page.getByTestId('merchant-order-card').first()).toBeVisible();
    expect(await activeTabIndex(page)).toBe(0);
  });

  test('D6. 最近优惠券下钻：点击行 → /merchant/vouchers，列表真实加载', async ({ page }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isVouchersMy(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;
    await expect(page.getByTestId('recent-voucher')).toHaveCount(5);

    const vouchersResp = page.waitForResponse((r) => isVouchersMy(r.url(), r.request().method()), {
      timeout: 20_000,
    });
    await page.getByTestId('recent-voucher').first().click();
    await expect(page).toHaveURL(/\/merchant\/vouchers/);
    expect((await (await vouchersResp).json()).success).toBe(true);
    await expect(page.getByTestId('voucher-card').first()).toBeVisible();
  });

  test('D7. 下钻一致性：Dashboard 数 == 下钻后 OrderList total（total≠页长证据保留）', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isMerchantOrders(r.url(), r.request().method()));
    await page.goto('/merchant/dashboard');
    await first;

    const dashPending = await page.getByTestId('stat-pending-value').innerText();
    const dashRedeemed = await page.getByTestId('stat-redeemed-value').innerText();

    // 待核销链：Dashboard N → 下钻 status=2 total N（同一真实数据源）
    let totalPromise = waitMerchantOrdersTotal(page, '2');
    await page.getByTestId('stat-pending').click();
    await expect(page).toHaveURL(/tab=pending/);
    expect(String(await totalPromise)).toBe(dashPending.trim());

    // 回 Dashboard 再走已核销链
    await page.goto('/merchant/dashboard');
    await page.waitForResponse((r) => isMerchantOrders(r.url(), r.request().method()));
    totalPromise = waitMerchantOrdersTotal(page, '3');
    await page.getByTestId('stat-redeemed').click();
    await expect(page).toHaveURL(/tab=completed/);
    expect(String(await totalPromise)).toBe(dashRedeemed.trim());

    // DB 终审：两链 total 与库一致
    expect(countMerchantOrdersByPhones([MERCHANT_A_PHONE], 2)).toBe(EXPECT_PENDING_A);
    expect(countMerchantOrdersByPhones([MERCHANT_A_PHONE], 3)).toBe(EXPECT_REDEEMED_A);
  });

  test('D8. query 不破坏手动 Tab：?tab=pending 进入后可自由切换 全部/已核销', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    const first = page.waitForResponse((r) => isMerchantOrders(r.url(), r.request().method()));
    await page.goto('/merchant/orders?tab=pending');
    await first;
    expect(await activeTabIndex(page)).toBe(1);

    // 手动切「全部」：请求不再带 status（与 Step 1 既有语义一致）
    const allUrls: string[] = [];
    page.on('request', (r) => {
      if (isMerchantOrders(r.url(), r.method())) allUrls.push(r.url());
    });
    await page.locator('.van-tab').nth(0).click();
    await expect(page.getByText(`共 ${EXPECT_PENDING_A + EXPECT_REDEEMED_A} 条`)).toBeVisible();
    expect(await activeTabIndex(page)).toBe(0);
    const lastAll = new URL(allUrls[allUrls.length - 1]);
    expect(lastAll.searchParams.get('status')).toBeNull();

    // 再切「已核销」：status=3
    const completedTotal = waitMerchantOrdersTotal(page, '3');
    await page.locator('.van-tab').nth(2).click();
    expect(await completedTotal).toBe(EXPECT_REDEEMED_A);
    expect(await activeTabIndex(page)).toBe(2);
  });
});
