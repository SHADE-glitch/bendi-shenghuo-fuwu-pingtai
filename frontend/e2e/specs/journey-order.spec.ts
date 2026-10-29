import { expect, test } from '@playwright/test';
import { cleanupMyOrders } from '../helpers/cleanup';

/**
 * 真实后端全流程 · 优惠券订单生命周期
 * 覆盖：店铺详情领取普通券 → 我的优惠券 → 支付 → 退款 → 删除。
 *
 * 说明：用例刻意选择「普通券（type=0）」而不是秒杀券，
 * 因为普通券不消耗 tb_seckill_voucher 库存，也不会写入 Redis 秒杀占位集合，
 * 从而保证冻结的秒杀亮点数据（库存 / 并发基线）不被 e2e 干扰。
 */
test.describe('优惠券订单：领取 → 支付 → 退款 → 删除', () => {
  test.beforeAll(async () => {
    await cleanupMyOrders();
  });

  test.afterAll(async () => {
    await cleanupMyOrders();
  });

  test('普通券走完完整订单状态流转', async ({ page }) => {
    // ---- 1. 店铺详情领取 ----
    await page.goto('/shop/1');
    const claimButton = page.getByRole('button', { name: '立即领取' }).first();
    await expect(claimButton).toBeVisible();
    await claimButton.click();

    // 领取成功弹窗 → 去支付
    const successDialog = page.locator('.van-dialog');
    await expect(successDialog).toContainText('领取成功');
    await successDialog.getByRole('button', { name: '去支付' }).click();
    await expect(page).toHaveURL(/\/my-vouchers/);

    // ---- 2. 我的优惠券：待支付 → 支付 ----
    const card = page.locator('.voucher-card').first();
    await expect(card).toBeVisible();
    const title = await card.locator('h3').innerText();

    await card.getByRole('button', { name: '去支付' }).click();
    await page.getByRole('button', { name: '确认支付' }).click();
    await expect(page.locator('.voucher-card').first().getByRole('button', { name: '申请退款' })).toBeVisible();

    // ---- 3. 退款 ----
    await page.locator('.voucher-card').first().getByRole('button', { name: '申请退款' }).click();
    await page.getByRole('button', { name: '确认' }).click();
    // 退款后订单状态变为已退款，从未使用 Tab 消失
    await expect(page.locator('.voucher-card').filter({ hasText: title })).toHaveCount(0, { timeout: 15_000 });

    // ---- 4. 在「已取消/退款」Tab 找到并删除 ----
    // 券 tab 已按订单终态拆成 4 个：未使用 / 已使用 / 已过期 / 已取消退款。
    // 退款单是 REFUNDED(6)，落在第 4 个 tab（invalid = CANCELLED(4) + REFUNDED(6)，
    // 见 VoucherOrderServiceImpl 的 tab→status 映射），不再是第 3 个「已过期」。
    await page.locator('.van-tab').nth(3).click();
    const refundedCard = page.locator('.voucher-card').filter({ hasText: title });
    await expect(refundedCard).toBeVisible();
    await refundedCard.getByRole('button', { name: '删除' }).click();
    await page.getByRole('button', { name: '确认' }).click();
    await expect(page.locator('.voucher-card').filter({ hasText: title })).toHaveCount(0);
  });
});
