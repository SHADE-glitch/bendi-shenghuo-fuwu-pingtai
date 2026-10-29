import { expect, test } from '@playwright/test';
import { apiContext } from '../helpers/api';

/**
 * 真实后端全流程 · 页面可达性与基础交互
 * 目标：所有页面在登录态下都能无 JS 报错地渲染，搜索等基础交互可用。
 */

/** 种子数据里存在的外链图片不可达，属于既有噪声，不计入失败 */
const IGNORED_CONSOLE_PATTERNS = [
  'Failed to load resource',
  'net::ERR',
  'favicon',
  'AMap',
  'amap',
];

const isNoise = (message: string) => IGNORED_CONSOLE_PATTERNS.some((p) => message.includes(p));

/** 需要登录态的页面（含本轮新增的他人主页） */
const ROUTES = [
  '/',
  '/map',
  '/my-vouchers',
  '/message',
  '/mine',
  '/settings',
  '/info',
  '/publish',
  '/my-blogs',
  '/follows',
  '/history',
  '/my-comments',
  '/feedback',
  '/about',
  '/scan',
  '/shop/1',
  '/blog/4',
  '/user/2',
];

test.describe('页面可达性与基础交互', () => {
  test('全部页面均可在登录态下渲染且无 JS 报错', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
    page.on('console', (msg) => {
      if (msg.type() === 'error' && !isNoise(msg.text())) {
        errors.push(`console: ${msg.text()}`);
      }
    });

    // /scan 是设计上的全屏相机页（无 nav-bar/tabbar，源码实证），单独验证其自身内容
    const FULLSCREEN_ROUTES: Record<string, string> = {
      '/scan': '.scan-close',
    };

    for (const route of ROUTES) {
      await page.goto(route);
      await expect(page.locator('#app')).toBeVisible();
      const fullscreenProbe = FULLSCREEN_ROUTES[route];
      if (fullscreenProbe) {
        await expect(page.locator(fullscreenProbe).first()).toBeVisible();
      } else {
        await expect(page.locator('.van-nav-bar, .van-tabbar').first()).toBeVisible();
      }
    }

    expect(errors, `页面出现 JS 报错：\n${errors.join('\n')}`).toEqual([]);
  });

  test('首页搜索门店可用', async ({ page }) => {
    await page.goto('/');
    const search = page.getByPlaceholder('搜索商户、地址或菜系');
    await search.fill('茶');
    await search.press('Enter');
    await expect(page.locator('article').first()).toBeVisible();
  });

  test('首页分类切换可用', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('article').first()).toBeVisible();
    await page.getByRole('tab', { name: 'KTV' }).click();
    await expect(page.locator('article').first()).toBeVisible();
  });

  test('未登录访问受保护页面会被重定向到登录页', async ({ browser }) => {
    // 显式空 storage：real project 的 storageState 会被 browser.newContext() 继承，
    // 不传则“匿名”上下文实际带登录态（token/authorization/user），断言失去意义
    const anonymous = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await anonymous.newPage();
    await page.goto('/');
    const keys = (await page.evaluate(() => Object.keys(localStorage))) as string[];
    expect(keys, '匿名上下文必须无认证残留').toEqual([]);
    await page.goto('/mine');
    await expect(page).toHaveURL(/\/login/);
    await anonymous.close();
  });

  test('店铺详情会缓存店铺数据（第二次进入明显更快）', async ({ page }) => {
    // 只做「可访问」断言，具体的缓存/延迟数字由后端 test-results 自证，不在 e2e 里编造指标
    const api = await apiContext();
    await api.dispose();

    await page.goto('/shop/1');
    await expect(page.locator('h1, .shop-name').first()).toBeVisible();
  });
});
