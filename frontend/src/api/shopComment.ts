import client from './client';
import {
  Result,
  ShopCommentCreateRequest,
  ShopCommentDTO,
  ShopCommentSummaryDTO,
  ShopCommentUpdateRequest,
} from './types';

/**
 * 对应后端 ShopCommentController（商铺评价）
 * <p>列表 / 汇总为公开接口，写入 / 我的评价需登录。</p>
 */
export const shopCommentService = {
  /**
   * 分页查询某店铺的评价（公开）
   * GET /shops/{shopId}/comments?current=&size=
   */
  listByShop(shopId: number | string, current = 1, size = 10) {
    return client.get<Result<ShopCommentDTO[]>>(`/shops/${shopId}/comments`, {
      params: { current, size },
    });
  },

  /**
   * 查询某店铺的评价汇总（公开）
   * GET /shops/{shopId}/comments/summary
   */
  summary(shopId: number | string) {
    return client.get<Result<ShopCommentSummaryDTO>>(`/shops/${shopId}/comments/summary`);
  },

  /**
   * 发布评价
   * POST /shop-comments
   */
  create(payload: ShopCommentCreateRequest) {
    return client.post<Result<number | string>>('/shop-comments', payload);
  },

  /**
   * 分页查询我的评价
   * GET /shop-comments/mine?current=&size=
   */
  listMine(current = 1, size = 10) {
    return client.get<Result<ShopCommentDTO[]>>('/shop-comments/mine', {
      params: { current, size },
    });
  },

  /**
   * 删除我的评价
   * DELETE /shop-comments/{commentId}
   */
  remove(commentId: number | string) {
    return client.delete<Result<void>>(`/shop-comments/${commentId}`);
  },

  /**
   * 编辑我的评价（仅作者本人）
   * PUT /shop-comments/{commentId}
   */
  update(commentId: number | string, payload: ShopCommentUpdateRequest) {
    return client.put<Result<void>>(`/shop-comments/${commentId}`, payload);
  },
};
