import { apiContext } from './api';
import { E2E_PREFIX } from './env';

/**
 * 收尾清理：把带 E2E- 前缀的造数从真实库里删掉，保证可重复执行。
 * <p>
 * 用例正常路径应当自己清理；这里是「断言失败/中断」时的兜底，
 * 只删除本套件的造数（前缀匹配），不触碰种子数据。
 * </p>
 */
export async function cleanupE2eData(): Promise<void> {
  const api = await apiContext();
  try {
    // 1. 笔记（顺便级联清掉其评论与点赞集合）
    const blogsRes = await api.get('/blogs/mine', { params: { current: 1, size: 50 } });
    if (blogsRes.ok()) {
      const blogs = ((await blogsRes.json())?.data ?? []) as Array<{ id: string; title?: string }>;
      for (const blog of blogs) {
        if ((blog.title ?? '').startsWith(E2E_PREFIX)) {
          await api.delete(`/blogs/${blog.id}`);
        }
      }
    }

    // 2. 我发表的笔记评论
    const commentsRes = await api.get('/blogs/comments/mine', { params: { current: 1, size: 50 } });
    if (commentsRes.ok()) {
      const comments = ((await commentsRes.json())?.data ?? []) as Array<{
        id: string;
        blogId: string;
        content?: string;
      }>;
      for (const c of comments) {
        if ((c.content ?? '').startsWith(E2E_PREFIX)) {
          await api.delete(`/blogs/${c.blogId}/comments/${c.id}`);
        }
      }
    }

    // 3. 商铺评价
    const shopCommentsRes = await api.get('/shop-comments/mine', { params: { current: 1, size: 50 } });
    if (shopCommentsRes.ok()) {
      const items = ((await shopCommentsRes.json())?.data ?? []) as Array<{ id: string; content?: string }>;
      for (const item of items) {
        if ((item.content ?? '').startsWith(E2E_PREFIX)) {
          await api.delete(`/shop-comments/${item.id}`);
        }
      }
    }

    // 4. 意见反馈
    const feedbackRes = await api.get('/feedbacks/mine', { params: { current: 1, size: 50 } });
    if (feedbackRes.ok()) {
      const items = ((await feedbackRes.json())?.data ?? []) as Array<{ id: string; content?: string }>;
      for (const item of items) {
        if ((item.content ?? '').startsWith(E2E_PREFIX)) {
          await api.delete(`/feedbacks/${item.id}`);
        }
      }
    }

    // 5. 浏览历史（用例会被中断在任意一步，统一清空本人历史）
    await api.delete('/browse-history');
  } finally {
    await api.dispose();
  }
}

/**
 * 清掉当前 e2e 账号的全部订单：待支付先取消 / 已支付先退款，再删除记录。
 * 用于订单用例失败中断后的兜底，保证下次运行可以从「零订单」出发。
 */
export async function cleanupMyOrders(): Promise<void> {
  const api = await apiContext();
  try {
    for (const tab of ['unused', 'used', 'expired', 'invalid']) {
      const res = await api.get('/voucher-orders/mine', { params: { tab, current: 1, size: 50 } });
      if (!res.ok()) continue;
      const items = ((await res.json())?.data ?? []) as Array<{ orderId: string; orderStatus: number }>;
      for (const item of items) {
        if (item.orderStatus === 1) {
          await api.post(`/voucher-orders/${item.orderId}/cancel`);
        } else if (item.orderStatus === 2) {
          await api.post(`/voucher-orders/${item.orderId}/refund`);
        }
        await api.delete(`/voucher-orders/${item.orderId}`);
      }
    }
  } finally {
    await api.dispose();
  }
}

/**
 * 清掉指定券的残留订单：先退款/取消再删除，保证订单表回到干净状态。
 */
export async function cleanupOrdersOfVoucher(voucherId: string): Promise<void> {
  const api = await apiContext();
  try {
    for (const tab of ['unused', 'used', 'expired', 'invalid']) {
      const res = await api.get('/voucher-orders/mine', { params: { tab, current: 1, size: 50 } });
      if (!res.ok()) continue;
      const items = ((await res.json())?.data ?? []) as Array<{
        orderId: string;
        voucherId: string;
        orderStatus: number;
      }>;
      for (const item of items) {
        if (String(item.voucherId) !== String(voucherId)) continue;
        if (item.orderStatus === 1) {
          await api.post(`/voucher-orders/${item.orderId}/cancel`);
        } else if (item.orderStatus === 2) {
          await api.post(`/voucher-orders/${item.orderId}/refund`);
        }
        await api.delete(`/voucher-orders/${item.orderId}`);
      }
    }
  } finally {
    await api.dispose();
  }
}
