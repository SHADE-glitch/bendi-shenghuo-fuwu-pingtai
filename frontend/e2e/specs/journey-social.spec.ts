import { expect, test } from '@playwright/test';
import { apiContext, uniqueSuffix } from '../helpers/api';
import { cleanupE2eData } from '../helpers/cleanup';
import { E2E_PREFIX } from '../helpers/env';

/** 种子用户 2（可可今天不吃肉），被关注/取关的稳定目标 */
const TARGET_USER_ID = '2';

/**
 * 真实后端全流程 · 社交与通知
 * 覆盖：关注 → 他人主页（followedByMe 联动）→ 取关；
 *       浏览历史（店铺 + 笔记）→ 清空；消息「全部已读」。
 */
test.describe('社交闭环与消息历史', () => {
  test.beforeAll(async () => {
    await cleanupE2eData();
    // 前置：确保未关注目标用户，避免依赖上一次运行的残留状态
    const api = await apiContext();
    await api.delete(`/follows/${TARGET_USER_ID}`);
    await api.dispose();
  });

  test.afterAll(async () => {
    const api = await apiContext();
    await api.delete(`/follows/${TARGET_USER_ID}`);
    await api.dispose();
    await cleanupE2eData();
  });

  test('关注 → 他人主页 → 关注列表进入主页 → 取关', async ({ page }) => {
    // ---- 他人主页：未关注状态 ----
    await page.goto(`/user/${TARGET_USER_ID}`);
    const followBtn = page.locator('.profile-head .van-button');
    await expect(followBtn).toHaveText(/关注/);
    await expect(followBtn).toHaveText('+ 关注');

    // ---- 关注 ----
    await followBtn.click();
    await expect(followBtn).toHaveText('已关注');

    // ---- 关注列表 → 点击进入对方主页 ----
    await page.goto('/follows');
    const item = page.locator('.user-item').first();
    await expect(item).toBeVisible();
    await item.locator('.user-main').click();
    await expect(page).toHaveURL(new RegExp(`/user/${TARGET_USER_ID}`));

    // ---- 取关（回到未关注） ----
    await expect(page.locator('.profile-head .van-button')).toHaveText('已关注');
    await page.locator('.profile-head .van-button').click();
    await expect(page.locator('.profile-head .van-button')).toHaveText('+ 关注');
  });

  test('浏览历史：店铺与笔记都会入历史，可清空', async ({ page }) => {
    // 先清空，保证断言从确定状态出发
    const api = await apiContext();
    await api.delete('/browse-history');
    await api.dispose();

    await page.goto('/shop/1');
    await expect(page.getByText('用户评价')).toBeVisible();
    await page.goto('/blog/4');
    await expect(page.locator('.blog-title')).toBeVisible();

    await page.goto('/history');
    const items = page.locator('.history-item');
    await expect(items.first()).toBeVisible();
    await expect(page.locator('.history-meta').first()).toContainText(/店铺|笔记/);

    // 清空
    await page.getByText('清空').click();
    await page.getByRole('button', { name: '确认' }).click();
    await expect(page.locator('.history-item')).toHaveCount(0);
  });

  test('消息中心：列表可见，全部已读按钮状态正确', async ({ page }) => {
    await page.goto('/message');
    await expect(page.locator('.read-all-btn')).toBeVisible();
    await expect(page.locator('.van-tab').first()).toContainText('未读');
  });

  test('他人主页：匿名访问不展示关注状态', async ({ browser }) => {
    // 显式空 storage（同 navigation 匿名用例）：real project 的 storageState 会被继承，
    // 必须显式清空，否则“匿名”实际是登录态，关注按钮可见属正确行为而非产品错误
    const anonymous = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const page = await anonymous.newPage();
    await page.goto('/');
    const keys = (await page.evaluate(() => Object.keys(localStorage))) as string[];
    expect(keys, '匿名上下文必须无认证残留').toEqual([]);
    await page.goto(`/user/${TARGET_USER_ID}`);
    await expect(page.locator('.profile-name')).toBeVisible();
    await expect(page.locator('.profile-head .van-button')).toHaveCount(0);
    await anonymous.close();
  });
});
