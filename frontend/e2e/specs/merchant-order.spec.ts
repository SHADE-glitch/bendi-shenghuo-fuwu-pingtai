import { expect, request, test, type Page } from '@playwright/test';
import { E2E_API_BASE } from '../helpers/env';
import {
  cleanupMerchants,
  countE2eResidue,
  createMerchantFixture,
  getOrderRow,
  loginUser,
  CONSUMER_PHONE,
  MERCHANT_A_PHONE,
  MERCHANT_B_PHONE,
  type MerchantFixture,
} from '../helpers/merchant';

/**
 * Merchant Order 真实浏览器 E2E（Phase 4.5）
 *
 * 真实链路：真实浏览器 → 真实前端（vite dev）→ 真实登录态（role=1）→ 真实后端 → 真实 MySQL。
 * 不使用任何 mock / route interception / 静态 JSON。
 *
 * 用例顺序有意为之（文件内按声明顺序串行执行，workers=1 / fullyParallel=false）：
 * 1–3 需要订单处于 PAID(2)；4 会把它核销成 USED(3)；5–6 与订单状态无关。
 * 若 4 失败，`retries: 0` 保证不会因状态已变更而产生误导性的重试结果。
 */
const MERCHANT_ORDERS_API = '/api/voucher-orders/merchant';

/** 把登录 token 注入 localStorage，模拟「已登录」后再进入目标页 */
async function loginInBrowser(page: Page, token: string): Promise<void> {
  await page.goto('/');
  await page.evaluate((t) => {
    localStorage.setItem('authorization', t);
    localStorage.setItem('token', t);
  }, token);
}

const isMerchantListRequest = (url: string) => url.includes(MERCHANT_ORDERS_API);

test.describe('Merchant Order 真实浏览器 E2E', () => {
  let merchantA: MerchantFixture;
  let merchantB: MerchantFixture;
  let consumerToken: string;

  test.beforeAll(async () => {
    // 兜底：清掉上次中断可能残留的造数
    await cleanupMerchants([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    consumerToken = (await loginUser(CONSUMER_PHONE)).token;
    merchantA = await createMerchantFixture(MERCHANT_A_PHONE, 'A', consumerToken);
    merchantB = await createMerchantFixture(MERCHANT_B_PHONE, 'B', consumerToken);
  });

  test.afterAll(async () => {
    await cleanupMerchants([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    const residue = countE2eResidue([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    // 造数必须清零（否则视为测试污染）
    expect(residue).toEqual({ shops: 0, users: 0 });
  });

  test('1. role=1 商家可进入 /merchant/orders，并真实调用 GET /voucher-orders/merchant', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantA.token);

    const listResponse = page.waitForResponse(
      (r) => isMerchantListRequest(r.url()) && r.request().method() === 'GET'
    );
    await page.goto('/merchant/orders');

    const resp = await listResponse;
    expect(resp.status()).toBe(200);
    expect((await resp.json()).success).toBe(true);

    await expect(page).toHaveURL(/\/merchant\/orders/);
    await expect(page.getByText('商家订单', { exact: true }).first()).toBeVisible();
  });

  test('2. Mine 显示商家入口；订单列表展示真实 PAID 订单与核销按钮', async ({ page }) => {
    await loginInBrowser(page, merchantA.token);
    await page.goto('/mine');

    const entry = page.getByText('商家管理', { exact: true });
    await expect(entry).toBeVisible();

    const listResponse = page.waitForResponse(
      (r) => isMerchantListRequest(r.url()) && r.request().method() === 'GET'
    );
    await entry.click();
    await expect(page).toHaveURL(/\/merchant\/orders/);
    await listResponse;

    const card = page
      .locator('[data-testid="merchant-order-card"]')
      .filter({ hasText: merchantA.orderId });
    await expect(card).toBeVisible();
    await expect(card).toContainText('已支付');
    await expect(card).toContainText(`${'E2E-'}券-A`);
    await expect(card).toContainText('E2E-Shop-A');
    await expect(card).toContainText('¥10');
    // Long 订单号以字符串原样展示（未被 Number 精度转换）
    await expect(card).toContainText(merchantA.orderId);
    await expect(card.locator('[data-testid="redeem-button"]')).toHaveCount(1);
  });

  test('3. 状态筛选：全部 / 待核销 / 已核销 的真实请求参数正确', async ({ page }) => {
    await loginInBrowser(page, merchantA.token);

    const urls: string[] = [];
    page.on('request', (r) => {
      if (isMerchantListRequest(r.url())) urls.push(r.url());
    });

    const first = page.waitForResponse(
      (r) => isMerchantListRequest(r.url()) && r.request().method() === 'GET'
    );
    await page.goto('/merchant/orders');
    await first;

    const last = () => new URL(urls[urls.length - 1]);
    expect(last().searchParams.get('current')).toBe('1');
    expect(last().searchParams.get('size')).toBe('10');
    expect(last().searchParams.get('status')).toBeNull(); // 全部：不传 status

    // 待核销 → status=2，能看到 A 的 PAID 订单
    await page.locator('.van-tab').nth(1).click();
    await expect(
      page.locator('[data-testid="merchant-order-card"]').filter({ hasText: merchantA.orderId })
    ).toBeVisible();
    expect(last().searchParams.get('status')).toBe('2');

    // 已核销 → status=3，核销前为空
    await page.locator('.van-tab').nth(2).click();
    await expect(page.getByText('暂无订单')).toBeVisible();
    expect(last().searchParams.get('status')).toBe('3');
  });

  test('4. 真实核销闭环：POST redeem → DB status=3 + use_time → 重新 GET → UI 更新', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantA.token);

    const initialList = page.waitForResponse(
      (r) => isMerchantListRequest(r.url()) && r.request().method() === 'GET'
    );
    await page.goto('/merchant/orders');
    await initialList;

    const card = page
      .locator('[data-testid="merchant-order-card"]')
      .filter({ hasText: merchantA.orderId });
    await expect(card.locator('[data-testid="order-status"]')).toHaveText('已支付');
    await expect(card).toContainText('核销：-');

    // 点击「核销」→ 出现真实确认框
    await card.locator('[data-testid="redeem-button"]').click();
    const dialog = page.locator('.van-dialog');
    await expect(dialog).toContainText('确认核销');
    await expect(dialog).toContainText('确定要核销这张优惠券吗？');

    // 确认 → 监听真实 POST /api/voucher-orders/{orderId}/redeem 与随后的重新 GET
    const redeemResponse = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/voucher-orders/${merchantA.orderId}/redeem`) &&
        r.request().method() === 'POST'
    );
    const refreshResponse = page.waitForResponse(
      (r) => isMerchantListRequest(r.url()) && r.request().method() === 'GET'
    );
    await page.getByRole('button', { name: '确认' }).click();

    const redeem = await redeemResponse;
    expect(redeem.status()).toBe(200);
    expect((await redeem.json()).success).toBe(true);
    // POST URL 中的 orderId 保持字符串精度
    expect(redeem.url()).toContain(merchantA.orderId);
    await refreshResponse;

    // ---- 后端真实状态（DB） ----
    const row = getOrderRow(merchantA.orderId);
    expect(row.status).toBe(3);
    expect(row.useTime).not.toBeNull();

    // ---- UI 最终状态（全部 Tab） ----
    await expect(card.locator('[data-testid="order-status"]')).toHaveText('已核销');
    await expect(card).not.toContainText('核销：-');
    await expect(card.locator('[data-testid="redeem-button"]')).toHaveCount(0);

    // 待核销 Tab：该订单消失
    await page.locator('.van-tab').nth(1).click();
    await expect(page.getByText('暂无订单')).toBeVisible();

    // 已核销 Tab：该订单出现
    await page.locator('.van-tab').nth(2).click();
    await expect(
      page.locator('[data-testid="merchant-order-card"]').filter({ hasText: merchantA.orderId })
    ).toBeVisible();
  });

  test('5. 权限：role=0 无商家入口且被前端拦截；后端仍 403/40003；匿名跳转登录', async ({
    page,
  }) => {
    // role=0（种子消费者）
    await loginInBrowser(page, consumerToken);
    await page.goto('/mine');
    await expect(page.getByText('商家管理', { exact: true })).toHaveCount(0);

    // 手动输入 /merchant/orders → 前端 UX 拦截回 /mine
    await page.goto('/merchant/orders');
    await expect(page).toHaveURL(/\/mine/);

    // 后端仍是最终安全边界：role=0 直连接口 → 403 + 40003
    const api = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: consumerToken },
    });
    try {
      const res = await api.get('/voucher-orders/merchant?current=1&size=10');
      expect(res.status()).toBe(403);
      expect((await res.json()).errorCode).toBe('40003');
    } finally {
      await api.dispose();
    }

    // 匿名 → 跳转登录
    await page.evaluate(() => localStorage.clear());
    await page.goto('/merchant/orders');
    await expect(page).toHaveURL(/\/login/);
  });

  test('6. 跨商家隔离：Merchant A 看不到 Merchant B 的订单', async ({ page }) => {
    await loginInBrowser(page, merchantA.token);

    const listResponse = page.waitForResponse(
      (r) => isMerchantListRequest(r.url()) && r.request().method() === 'GET'
    );
    await page.goto('/merchant/orders');
    await listResponse;

    const cards = page.locator('[data-testid="merchant-order-card"]');
    await expect(cards.filter({ hasText: merchantA.orderId })).toBeVisible();
    await expect(cards.filter({ hasText: merchantB.orderId })).toHaveCount(0);

    // B 的订单确实存在，只是在 B 名下（证明上面不是「B 根本没有订单」）
    const api = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantB.token },
    });
    try {
      const res = await api.get('/voucher-orders/merchant?current=1&size=10');
      const body = (await res.json()) as { data?: Array<{ orderId: string }> };
      expect((body.data ?? []).map((i) => i.orderId)).toContain(merchantB.orderId);
    } finally {
      await api.dispose();
    }
  });
});
