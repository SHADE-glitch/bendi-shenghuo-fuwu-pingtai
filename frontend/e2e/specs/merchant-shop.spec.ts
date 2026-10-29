import { expect, request, test, type Page } from '@playwright/test';
import { E2E_API_BASE } from '../helpers/env';
import {
  cleanupMerchants,
  countE2eFullResidue,
  ensureMerchantRole,
  getShopRow,
  loginUser,
  CONSUMER_PHONE,
  MERCHANT_A_PHONE,
  MERCHANT_B_PHONE,
} from '../helpers/merchant';

/**
 * Merchant Shop 真实浏览器 E2E（Phase 4.6 Step 3）
 *
 * 真实链路：真实浏览器 → 真实前端（vite dev）→ 真实登录态（role=1）→
 * 真实后端 → 真实 MySQL + 真实 Redis。
 * 不使用任何 mock / page.route() / HAR / vi.mock() / fake API。
 *
 * 造数纪律（与 Phase 4.5 一致）：
 * - 成为商家必须直写一次 DB（`UPDATE tb_user SET role=1`，无角色提升接口）——
 *   这是本套件**唯二**的 DB 写入（另一处是查库断言，只读）；
 * - 店铺数据一律走真实 UI（POST /shops / PUT /shops/{id}）创建，绝不 INSERT；
 * - 店名带 `E2E-Merchant-Shop-<timestamp>`，测后按 phone 整体删除。
 *
 * 用例顺序有意为之（文件内串行，workers=1 / fullyParallel=false）：
 * 1 入口+列表 → 2 校验拦截 → 3 新增闭环 → 4 编辑闭环 → 5 reload 持久性 →
 * 6 取消/重置 → 7 role=0 → 8 匿名 → 9 跨商家隔离。
 * 3 产出的 shopId（字符串原样透传，不 Number()）是 4–7、9 的前置。
 * 2 结尾的「取消」使 3 开头能断言表单复位（§17）。
 */

const MY_SHOPS_PATH = '/api/shops/my';
const SHOPS_PATH = '/api/shops';

/** 把登录 token 注入 localStorage，模拟「已登录」后再进入目标页 */
async function loginInBrowser(page: Page, token: string): Promise<void> {
  await page.goto('/');
  await page.evaluate((t) => {
    localStorage.setItem('authorization', t);
    localStorage.setItem('token', t);
  }, token);
}

const isMyShopsGet = (url: string, method: string): boolean => {
  try {
    const u = new URL(url);
    return u.pathname === MY_SHOPS_PATH && method === 'GET';
  } catch {
    return false;
  }
};

const isShopsPost = (url: string, method: string): boolean => {
  try {
    const u = new URL(url);
    return u.pathname === SHOPS_PATH && method === 'POST';
  } catch {
    return false;
  }
};

/** van-field 根 [data-testid] 下的真实 input */
const fieldInput = (page: Page, testId: string) => page.getByTestId(testId).locator('input');

/** 被选中的分类 radio（Vant 用 --checked 修饰类表达选中态） */
const selectedTypeOption = (page: Page) =>
  page.locator('[data-testid="type-option"]:has(.van-radio__icon--checked)');

test.describe('Merchant Shop 真实浏览器 E2E', () => {
  // E2E 店名唯一（timestamp 只在 helper/spec 内生成，不进页面代码）
  const TS = Date.now();
  const SHOP_NAME = `E2E-Merchant-Shop-${TS}`;
  const SHOP_ADDR = `E2E-Addr-${TS}`;
  const SHOP_HOURS = '10:00-22:00';
  const SHOP_X = '120.16';
  const SHOP_Y = '30.28';

  const EDITED_NAME = `E2E-Merchant-Shop-Edited-${TS}`;
  const EDITED_ADDR = `E2E-Addr-Edited-${TS}`;
  const EDITED_HOURS = '09:00-21:00';
  const EDITED_X = '120.18';
  const EDITED_Y = '30.29';

  let merchantAToken = '';
  let merchantAUserId = '';
  let merchantBToken = '';
  let consumerToken = '';
  /** POST /shops 返回的新店 id：字符串原样使用，绝不 Number()/parseInt() */
  let shopId = '';
  let totalShopsBefore = 0;

  test.beforeAll(async () => {
    // 兜底：清掉上次中断可能残留的造数
    await cleanupMerchants([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    const before = countE2eFullResidue([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    expect(before).toMatchObject({ shops: 0, users: 0, vouchers: 0, orders: 0 });
    totalShopsBefore = before.totalShops;

    consumerToken = (await loginUser(CONSUMER_PHONE)).token;
    const a = await ensureMerchantRole(MERCHANT_A_PHONE);
    merchantAToken = a.token;
    merchantAUserId = a.userId;
    merchantBToken = (await ensureMerchantRole(MERCHANT_B_PHONE)).token;
  });

  test.afterAll(async () => {
    await cleanupMerchants([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    const residue = countE2eFullResidue([MERCHANT_A_PHONE, MERCHANT_B_PHONE]);
    // 造数必须清零（否则视为测试污染）；种子店铺总数必须与跑前一致
    expect(residue).toMatchObject({ shops: 0, users: 0, vouchers: 0, orders: 0 });
    expect(residue.totalShops).toBe(totalShopsBefore);
  });

  test('1. Mine 显示「商家店铺」入口；进入 /merchant/shops 真实调用 GET /shops/my', async ({
    page,
  }) => {
    await loginInBrowser(page, merchantAToken);
    await page.goto('/mine');

    // 「商家店铺」与「商家管理」两个入口保持独立
    await expect(page.getByText('商家店铺', { exact: true })).toBeVisible();
    await expect(page.getByText('商家管理', { exact: true })).toBeVisible();

    const listResponse = page.waitForResponse(
      (r) => isMyShopsGet(r.url(), r.request().method()),
      { timeout: 20_000 }
    );
    await page.getByText('商家店铺', { exact: true }).click();
    await expect(page).toHaveURL(/\/merchant\/shops/);

    const resp = await listResponse;
    expect(resp.status()).toBe(200);
    expect((await resp.json()).success).toBe(true);

    // 列表页真实 DOM / 文案：标题 + 新增入口 + 空态（新商家尚无店铺）
    await expect(page.getByText('商家店铺', { exact: true }).first()).toBeVisible();
    await expect(page.getByTestId('shop-create-top')).toContainText('新增店铺');
    await expect(page.getByText('暂无店铺')).toBeVisible();
  });

  test('2. 前端校验真实生效：name 为空时阻止 POST，弹窗保持打开', async ({ page }) => {
    await loginInBrowser(page, merchantAToken);
    const firstList = page.waitForResponse((r) => isMyShopsGet(r.url(), r.request().method()));
    await page.goto('/merchant/shops');
    await firstList;

    let postCount = 0;
    page.on('request', (r) => {
      if (isShopsPost(r.url(), r.method())) postCount++;
    });

    await page.getByTestId('shop-create-top').click();
    await expect(page.getByRole('heading', { name: '新增店铺' })).toBeVisible();

    // 故意留空 name，其余填好
    await page.locator('[data-testid="type-option"]:has-text("美食")').first().click();
    await fieldInput(page, 'field-address').fill(SHOP_ADDR);
    await fieldInput(page, 'field-openHours').fill(SHOP_HOURS);

    await page.getByTestId('shop-save').click();

    // 校验 Toast 出现，且弹窗仍在（未提交、未关闭）
    await expect(page.locator('.van-toast').filter({ hasText: '请填写店铺名称' })).toBeVisible();
    await page.waitForTimeout(1500);
    expect(postCount).toBe(0);
    await expect(page.getByRole('heading', { name: '新增店铺' })).toBeVisible();

    // 取消，为用例 3 留下「复位可断言」的前置
    await page.getByTestId('shop-cancel').click();
    await expect(page.getByRole('heading', { name: '新增店铺' })).toHaveCount(0);
  });

  test('3. 新增闭环：UI 创建 → POST /shops → DB 落库 → 列表刷新出现新店', async ({ page }) => {
    await loginInBrowser(page, merchantAToken);
    const firstList = page.waitForResponse((r) => isMyShopsGet(r.url(), r.request().method()));
    await page.goto('/merchant/shops');
    await firstList;

    await page.getByTestId('shop-create-top').click();
    await expect(page.getByRole('heading', { name: '新增店铺' })).toBeVisible();

    // 用例 2 取消后表单必须复位：name/address/openHours 为空，x/y 回默认杭州坐标
    await expect(fieldInput(page, 'field-name')).toHaveValue('');
    await expect(fieldInput(page, 'field-address')).toHaveValue('');
    await expect(fieldInput(page, 'field-x')).toHaveValue('120.155');
    await expect(fieldInput(page, 'field-y')).toHaveValue('30.275');

    await fieldInput(page, 'field-name').fill(SHOP_NAME);
    await page.locator('[data-testid="type-option"]:has-text("美食")').first().click();
    await fieldInput(page, 'field-address').fill(SHOP_ADDR);
    await fieldInput(page, 'field-openHours').fill(SHOP_HOURS);
    await fieldInput(page, 'field-x').fill(SHOP_X);
    await fieldInput(page, 'field-y').fill(SHOP_Y);

    const postResponse = page.waitForResponse((r) => isShopsPost(r.url(), r.request().method()));
    const refreshResponse = page.waitForResponse((r) =>
      isMyShopsGet(r.url(), r.request().method())
    );
    await page.getByTestId('shop-save').click();

    // ---- 真实 POST /shops ----
    const post = await postResponse;
    expect(post.status()).toBe(200);
    const postBody = (await post.json()) as { success?: boolean; data?: string | number };
    expect(postBody.success).toBe(true);
    shopId = String(postBody.data ?? '');
    expect(shopId.length).toBeGreaterThan(0);

    // ---- 列表刷新（invalidateQueries 触发的重新 GET） ----
    await refreshResponse;
    const card = page.getByTestId('merchant-shop-card').filter({ hasText: SHOP_NAME });
    await expect(card).toBeVisible();
    await expect(card).toContainText(SHOP_ADDR);
    await expect(card).toContainText(SHOP_HOURS);
    await expect(card.locator('[data-testid="shop-type"]')).toContainText('美食');

    // ---- DB 直接验证 ----
    const row = getShopRow(shopId);
    expect(row.name).toBe(SHOP_NAME);
    expect(Number(row.typeId)).toBe(1);
    expect(row.address).toBe(SHOP_ADDR);
    expect(row.openHours).toBe(SHOP_HOURS);
    expect(row.x).toContain('120.16');
    expect(row.y).toContain('30.28');
    // owner 由后端从登录态注入，且服务端字段保持初始值
    expect(String(row.ownerId)).toBe(String(merchantAUserId));
    expect(row.images).toBe('[]');
    expect(row.sold).toBe(0);
    expect(row.comments).toBe(0);
    expect(row.score).toBe(0);
  });

  test('4. 编辑闭环：表单真实回填 → PUT /shops/{id} → DB 更新 → 列表刷新', async ({
    page,
  }) => {
    expect(shopId).toBeTruthy();
    await loginInBrowser(page, merchantAToken);
    const firstList = page.waitForResponse((r) => isMyShopsGet(r.url(), r.request().method()));
    await page.goto('/merchant/shops');
    await firstList;

    const card = page.getByTestId('merchant-shop-card').filter({ hasText: SHOP_NAME });
    await expect(card).toBeVisible();
    await card.getByTestId('shop-edit').click();
    await expect(page.getByRole('heading', { name: '编辑店铺' })).toBeVisible();

    // ---- 真实回填（GET /shops/my → DTO → openEdit() → Form） ----
    await expect(fieldInput(page, 'field-name')).toHaveValue(SHOP_NAME);
    await expect(fieldInput(page, 'field-address')).toHaveValue(SHOP_ADDR);
    await expect(fieldInput(page, 'field-openHours')).toHaveValue(SHOP_HOURS);
    await expect(fieldInput(page, 'field-x')).toHaveValue(SHOP_X);
    await expect(fieldInput(page, 'field-y')).toHaveValue(SHOP_Y);
    await expect(selectedTypeOption(page)).toContainText('美食');

    const before = getShopRow(shopId);

    await fieldInput(page, 'field-name').fill(EDITED_NAME);
    await fieldInput(page, 'field-address').fill(EDITED_ADDR);
    await fieldInput(page, 'field-openHours').fill(EDITED_HOURS);
    await fieldInput(page, 'field-x').fill(EDITED_X);
    await fieldInput(page, 'field-y').fill(EDITED_Y);

    const putResponse = page.waitForResponse((r) => {
      try {
        const u = new URL(r.url());
        return u.pathname === `${SHOPS_PATH}/${shopId}` && r.request().method() === 'PUT';
      } catch {
        return false;
      }
    });
    const refreshResponse = page.waitForResponse((r) =>
      isMyShopsGet(r.url(), r.request().method())
    );
    await page.getByTestId('shop-save').click();

    // ---- 真实 PUT（URL 中的 id 保持字符串精度） ----
    const put = await putResponse;
    expect(put.status()).toBe(200);
    expect((await put.json()).success).toBe(true);
    expect(put.url()).toContain(shopId);

    // ---- 列表刷新显示新名称 ----
    await refreshResponse;
    await expect(
      page.getByTestId('merchant-shop-card').filter({ hasText: EDITED_NAME })
    ).toBeVisible();

    // ---- DB 直接验证：编辑字段变更，归属/服务端字段未被污染 ----
    const after = getShopRow(shopId);
    expect(after.name).toBe(EDITED_NAME);
    expect(after.address).toBe(EDITED_ADDR);
    expect(after.openHours).toBe(EDITED_HOURS);
    expect(after.x).toContain('120.18');
    expect(after.y).toContain('30.29');
    expect(String(after.ownerId)).toBe(String(merchantAUserId));
    expect(String(after.ownerId)).toBe(String(before.ownerId));
    expect(after.images).toBe(before.images);
    expect(after.sold).toBe(before.sold);
    expect(after.comments).toBe(before.comments);
    expect(after.score).toBe(before.score);
  });

  test('5. Reload 持久性：刷新后修改仍存在（排除前端缓存假象）', async ({ page }) => {
    expect(shopId).toBeTruthy();
    await loginInBrowser(page, merchantAToken);
    await page.goto('/merchant/shops');
    await page.reload();
    const listResponse = page.waitForResponse((r) => isMyShopsGet(r.url(), r.request().method()));
    await page.goto('/merchant/shops');
    await listResponse;

    await expect(
      page.getByTestId('merchant-shop-card').filter({ hasText: EDITED_NAME })
    ).toBeVisible();
    expect(getShopRow(shopId).name).toBe(EDITED_NAME);
  });

  test('6. 取消/重置：新增不残留输入；编辑取消不污染 DB', async ({ page }) => {
    expect(shopId).toBeTruthy();
    await loginInBrowser(page, merchantAToken);
    const firstList = page.waitForResponse((r) => isMyShopsGet(r.url(), r.request().method()));
    await page.goto('/merchant/shops');
    await firstList;

    // 新增：填写后取消，再打开必须复位
    await page.getByTestId('shop-create-top').click();
    await fieldInput(page, 'field-name').fill(`E2E-JUNK-${Date.now()}`);
    await page.getByTestId('shop-cancel').click();
    await page.getByTestId('shop-create-top').click();
    await expect(fieldInput(page, 'field-name')).toHaveValue('');
    await page.getByTestId('shop-cancel').click();

    // 编辑：修改后取消，DB 原值仍在，再次打开回填仍是原值
    const card = page.getByTestId('merchant-shop-card').filter({ hasText: EDITED_NAME });
    await card.getByTestId('shop-edit').click();
    await fieldInput(page, 'field-name').fill(`E2E-JUNK-EDIT-${Date.now()}`);
    await page.getByTestId('shop-cancel').click();
    expect(getShopRow(shopId).name).toBe(EDITED_NAME);

    await card.getByTestId('shop-edit').click();
    await expect(fieldInput(page, 'field-name')).toHaveValue(EDITED_NAME);
    await page.getByTestId('shop-cancel').click();
  });

  test('7. 权限：role=0 无入口且被前端拦截；后端 POST/PUT 仍 403/40003', async ({ page }) => {
    expect(shopId).toBeTruthy();
    // role=0（种子消费者）
    await loginInBrowser(page, consumerToken);
    await page.goto('/mine');
    await expect(page.getByText('商家店铺', { exact: true })).toHaveCount(0);

    // 手动输入 /merchant/shops → 前端 UX 拦截回 /mine
    await page.goto('/merchant/shops');
    await expect(page).toHaveURL(/\/mine/);

    // 后端仍是最终安全边界：role=0 直接写接口 → 403 + 40003
    const api = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: consumerToken },
    });
    try {
      const postRes = await api.post('/shops', {
        data: {
          name: `E2E-FORBIDDEN-${Date.now()}`,
          typeId: 1,
          address: 'forbidden',
          openHours: SHOP_HOURS,
          x: 120.1,
          y: 30.2,
          images: '[]',
          sold: 0,
          comments: 0,
          score: 0,
        },
      });
      expect(postRes.status()).toBe(403);
      expect((await postRes.json()).errorCode).toBe('40003');

      const putRes = await api.put(`/shops/${shopId}`, {
        data: {
          name: `E2E-FORBIDDEN-${Date.now()}`,
          typeId: 1,
          address: 'forbidden',
          openHours: SHOP_HOURS,
          x: 120.1,
          y: 30.2,
        },
      });
      expect(putRes.status()).toBe(403);
      expect((await putRes.json()).errorCode).toBe('40003');
    } finally {
      await api.dispose();
    }
    // 拒绝后 A 的店铺不受影响
    expect(getShopRow(shopId).name).toBe(EDITED_NAME);
  });

  test('8. 匿名访问 /merchant/shops 进入登录流程', async ({ page }) => {
    await page.goto('/');
    await page.evaluate(() => localStorage.clear());
    await page.goto('/merchant/shops');
    await expect(page).toHaveURL(/\/login/);
  });

  test('9. 跨商家隔离：Merchant B 直改 Shop A 被 403/40004，DB 纹丝不动', async ({
    page,
  }) => {
    expect(shopId).toBeTruthy();
    expect(merchantBToken).toBeTruthy();

    // B 的「我的店铺」里看不到 A 的店（读隔离）
    const apiB = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantBToken },
    });
    try {
      const myRes = await apiB.get('/shops/my');
      expect(myRes.status()).toBe(200);
      const myBody = (await myRes.json()) as { success?: boolean; data?: Array<{ id?: unknown }> };
      expect(myBody.success).toBe(true);
      expect((myBody.data ?? []).map((s) => String(s.id))).not.toContain(shopId);
    } finally {
      await apiB.dispose();
    }

    // B 在浏览器里也看不到 A 的店
    await loginInBrowser(page, merchantBToken);
    const bList = page.waitForResponse((r) => isMyShopsGet(r.url(), r.request().method()));
    await page.goto('/merchant/shops');
    await bList;
    await expect(
      page.getByTestId('merchant-shop-card').filter({ hasText: EDITED_NAME })
    ).toHaveCount(0);

    // B 直接 PUT Shop A → 后端拒绝
    const before = getShopRow(shopId);
    const apiB2 = await request.newContext({
      baseURL: E2E_API_BASE,
      extraHTTPHeaders: { authorization: merchantBToken },
    });
    try {
      const res = await apiB2.put(`/shops/${shopId}`, {
        data: {
          name: `E2E-HIJACK-${Date.now()}`,
          typeId: 1,
          address: 'hijack',
          openHours: SHOP_HOURS,
          x: 120.1,
          y: 30.2,
        },
      });
      expect(res.status()).toBe(403);
      expect((await res.json()).errorCode).toBe('40004');
    } finally {
      await apiB2.dispose();
    }
    expect(getShopRow(shopId)).toEqual(before);
  });
});
