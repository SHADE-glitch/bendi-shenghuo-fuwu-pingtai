import { expect, test } from '@playwright/test';
import { uniqueSuffix } from '../helpers/api';
import { cleanupE2eData } from '../helpers/cleanup';
import { E2E_PREFIX } from '../helpers/env';

/**
 * 真实后端全流程 · 内容管理
 * 覆盖：发布笔记 → 详情 → 点赞 → 评论 → 我的笔记编辑 → 删除；
 *       店铺评价发布 → 编辑 → 删除；意见反馈提交 → 撤回。
 * 造数统一带 E2E- 前缀，收尾兜底清理，保证可重复执行。
 */
test.describe('内容管理全流程（笔记 / 评价 / 反馈）', () => {
  test.beforeAll(async () => {
    await cleanupE2eData();
  });

  test.afterAll(async () => {
    await cleanupE2eData();
  });

  test('笔记：发布 → 详情点赞评论 → 编辑 → 删除', async ({ page }) => {
    const title = `${E2E_PREFIX}自动化笔记${uniqueSuffix()}`;
    const editedTitle = `${title}-已编辑`;

    // ---- 发布 ----
    await page.goto('/publish');
    await expect(page.getByText('发布探店笔记').first()).toBeVisible();

    await page.getByRole('button', { name: /点击选择店铺/ }).click();
    await page.getByPlaceholder('搜索店铺名称').fill('茶');
    await page.getByPlaceholder('搜索店铺名称').press('Enter');
    await expect(page.locator('.shop-result').first()).toBeVisible();
    await page.locator('.shop-result').first().click();

    await page.getByPlaceholder('给你的探店笔记起个标题').fill(title);
    await page.getByPlaceholder(/记录这次探店的体验/).fill(`${E2E_PREFIX}端到端测试写入的正文内容。`);
    await page.getByText('发布', { exact: true }).click();

    await expect(page).toHaveURL(/\/my-blogs/);
    const card = page.locator('.blog-item').filter({ hasText: title });
    await expect(card).toBeVisible();

    // ---- 详情：点赞 ----
    await card.locator('article').click();
    // 作用域限定到详情容器：断言“当前打开的博客详情”，而非列表页任意同名标题
    // （我的笔记列表含种子同名笔记，直接全页定位会命中多处）
    const detail = page.locator('.blog-detail-page');
    await expect(detail).toBeVisible();
    await expect(detail.locator('.blog-title')).toHaveText(title);
    await detail.locator('.like-btn').click();
    await expect(detail.locator('.like-btn')).toContainText('1 赞');

    // ---- 详情：评论 ----
    const commentText = `${E2E_PREFIX}评论内容`;
    await page.getByPlaceholder('说点什么...').fill(commentText);
    await page.getByRole('button', { name: '发送' }).click();
    await expect(page.locator('.comment-text').filter({ hasText: commentText })).toBeVisible();

    // ---- 编辑（作者入口） ----
    await page.locator('.edit-btn', { hasText: '编辑' }).click();
    await expect(page).toHaveURL(/\/publish\?id=/);
    // 等编辑态回填完成（与保存写同一字段；不等就会被回填覆盖成原标题导致“保存无效”假象）
    await expect(page.getByPlaceholder('给你的探店笔记起个标题')).toHaveValue(title);
    await page.getByPlaceholder('给你的探店笔记起个标题').fill(editedTitle);
    await page.getByText('保存', { exact: true }).click();

    await expect(page).toHaveURL(/\/my-blogs/);
    const editedCard = page.locator('.blog-item').filter({ hasText: editedTitle });
    await expect(editedCard).toBeVisible();

    // ---- 删除（列表入口） ----
    await editedCard.getByRole('button', { name: '删除' }).click();
    await page.getByRole('button', { name: '确认' }).click();
    await expect(page.locator('.blog-item').filter({ hasText: editedTitle })).toHaveCount(0);
  });

  test('店铺评价：发布 → 编辑 → 删除', async ({ page }) => {
    const content = `${E2E_PREFIX}评价${uniqueSuffix()}`;
    const edited = `${content}-已编辑`;

    await page.goto('/shop/1');
    await expect(page.getByRole('button', { name: '写评价' })).toBeVisible();

    await page.getByRole('button', { name: '写评价' }).click();
    await page.locator('.van-popup .van-field__control').fill(content);
    await page.getByRole('button', { name: '发布' }).click();

    const item = page.locator('.comment-item').filter({ hasText: content });
    await expect(item).toBeVisible();

    // 编辑
    await item.getByText('编辑').click();
    await expect(page.locator('.editor-title')).toHaveText('编辑评价');
    await page.locator('.van-popup .van-field__control').fill(edited);
    await page.getByRole('button', { name: '保存' }).click();

    const editedItem = page.locator('.comment-item').filter({ hasText: edited });
    await expect(editedItem).toBeVisible();

    // 删除
    await editedItem.getByText('删除').click();
    await page.getByRole('button', { name: '确认' }).click();
    await expect(page.locator('.comment-item').filter({ hasText: edited })).toHaveCount(0);
  });

  test('意见反馈：提交 → 我的反馈 → 撤回', async ({ page }) => {
    const content = `${E2E_PREFIX}反馈${uniqueSuffix()}`;

    await page.goto('/feedback');
    await page.locator('.van-field__control').nth(1).fill(content);
    await page.getByRole('button', { name: '提交反馈' }).click();

    const card = page.locator('.feedback-card').filter({ hasText: content });
    await expect(card).toBeVisible();

    await card.getByRole('button', { name: '删除' }).click();
    await page.getByRole('button', { name: '确认' }).click();
    await expect(page.locator('.feedback-card').filter({ hasText: content })).toHaveCount(0);
  });
});
