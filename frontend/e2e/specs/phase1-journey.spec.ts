import { expect, test, type Page } from '@playwright/test';

/**
 * 网络隔离版冒烟：登录 → 首页 → 店铺详情 → 券动作 → 订单跳转
 *
 * 关键修复 1：原用例的 mock 路径与前端真实请求不一致
 * （`/api/user/**`、`/api/shop/type/list`、`/api/shop/1`、`/api/voucher/list/1` …），
 * 导致 mock 从未生效、请求实际打到真实后端，网络隔离形同虚设。
 * 这里按 `src/api/**` 的真实路径重写。
 *
 * 关键修复 2：路由匹配改用「URL 判定函数」而不是 `**​/api/**` 通配。
 * 因为 Vite 直接以 `/src/api/*.ts` 提供前端源码模块，`**​/api/**` 会把
 * `/src/api/client.ts` 也拦下来并返回 JSON，浏览器报
 * "Failed to load module script ... MIME type application/json"，页面整体白屏。
 */

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ success: true, errorCode: null, errorMsg: null, data, total: null }),
});

const SHOP = {
  id: 1,
  name: '演示精品火锅店',
  typeId: 1,
  images: '/imgs/shops/shop_1.jpg',
  area: '黄浦区',
  address: '南京东路888号',
  avgPrice: 120,
  sold: 999,
  score: 49,
};

const VOUCHER = {
  id: 10,
  shopId: 1,
  title: '100元精选双人餐券',
  subTitle: '全场通用',
  rules: '不与店内其他优惠叠加',
  payValue: 5000,
  actualValue: 10000,
  type: 1,
  stock: 20,
};

/** 只匹配后端接口前缀 /api/**，不会误伤 /src/api/** 这类源码模块 */
const isBackendApi = (url: URL) => url.pathname.startsWith('/api/');

async function mockBackend(page: Page) {
  /** 兜底命中记录，便于发现「漏 mock 的真实请求」 */
  const fallbacks: string[] = [];

  // 0) 兜底：任何未显式 mock 的后端请求都返回空成功。
  //    否则漏网请求会带着 mock token 打到真实后端，触发 401 → 前端登出并跳回登录页。
  //    注意：Playwright 后注册的 route 优先匹配，因此这一条必须最先注册。
  await page.route(isBackendApi, (route) => {
    const url = new URL(route.request().url());
    fallbacks.push(url.pathname + url.search);
    return route.fulfill(ok(null));
  });

  // 1) 验证码 / 登录 / 当前用户
  await page.route((u) => u.pathname === '/api/users/code', (route) => route.fulfill(ok('123456')));
  await page.route((u) => u.pathname === '/api/users/login', (route) =>
    route.fulfill(ok({ token: 'mock-test-token', user: { id: 1, nickName: '面试体验官', icon: '' } }))
  );
  await page.route((u) => u.pathname === '/api/users/me', (route) =>
    route.fulfill(ok({ id: 1, nickName: '面试体验官', icon: '' }))
  );

  // 2) 分类
  await page.route((u) => u.pathname === '/api/shop-types', (route) =>
    route.fulfill(ok([{ id: 1, name: '美食', sort: 1 }]))
  );

  // 3) 店铺：列表（/api/shops）与详情/评价（/api/shops/**）
  await page.route((u) => u.pathname === '/api/shops', (route) => route.fulfill(ok([SHOP])));
  await page.route((u) => u.pathname.startsWith('/api/shops/'), (route) => {
    const { pathname } = new URL(route.request().url());
    if (pathname.endsWith('/comments/summary')) {
      return route.fulfill(ok({ count: 0, avgRating: null }));
    }
    if (pathname.endsWith('/comments')) {
      return route.fulfill(ok([]));
    }
    return route.fulfill(ok(SHOP));
  });

  // 4) 券与订单
  await page.route((u) => u.pathname === '/api/vouchers', (route) => route.fulfill(ok([VOUCHER])));
  await page.route((u) => u.pathname.startsWith('/api/voucher-orders/seckill-orders'), (route) =>
    route.fulfill(ok({ orderId: 88888, voucherId: 10, orderStatus: 1, duplicateBlocked: false }))
  );
  await page.route((u) => u.pathname.endsWith('/status') && u.pathname.startsWith('/api/voucher-orders/'), (route) =>
    route.fulfill(ok(1))
  );

  // 5) 店铺详情页会按登录态请求「该店的笔记」
  await page.route((u) => u.pathname.startsWith('/api/blogs'), (route) => route.fulfill(ok([])));

  return fallbacks;
}

test('phase1 关键链路（网络隔离）：登录 → 首页 → 店铺 → 券动作 → 订单跳转', async ({ page }) => {
  const fallbackHits = await mockBackend(page);

  // ---- 登录 ----
  await page.goto('/login');
  await page.getByPlaceholder('请输入手机号').fill('13800138000');
  await page.getByPlaceholder('请输入验证码').fill('123456');
  await page.getByRole('button', { name: '登 录' }).click();

  // ---- 首页 ----
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByText('附近好店与限时优惠')).toBeVisible();
  await expect(page.locator('article').first()).toBeVisible();

  // ---- 店铺详情 ----
  await page.goto('/shop/1');
  await expect(page.getByText('特惠代金券')).toBeVisible();

  // ---- 券动作 ----
  const actionButton = page.getByRole('button', { name: '立即秒杀' }).first();
  await expect(actionButton).toBeVisible();
  await actionButton.click();

  // ---- 跳转秒杀排队页 ----
  await expect(page).toHaveURL(/\/order\/status\/88888/);

  // 兜底命中仅作观测输出：出现新增路径时便于补 mock，不影响用例结论
  if (fallbackHits.length) {
    console.log('[mock] 未显式 mock 的请求（已返回空成功）：', Array.from(new Set(fallbackHits)));
  }
});
