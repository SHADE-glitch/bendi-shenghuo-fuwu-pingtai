import client from './client';
import { BrowseHistoryCreateRequest, BrowseHistoryDTO, Result } from './types';

/**
 * 对应后端 BrowseHistoryController（浏览历史）
 */
export const historyService = {
  /**
   * 记录一次浏览（同一目标去重）
   * POST /browse-history
   */
  record(payload: BrowseHistoryCreateRequest) {
    return client.post<Result<void>>('/browse-history', payload);
  },

  /**
   * 分页查询我的浏览历史
   * GET /browse-history/mine?current=&size=
   */
  mine(current = 1, size = 10) {
    return client.get<Result<BrowseHistoryDTO[]>>('/browse-history/mine', {
      params: { current, size },
    });
  },

  /**
   * 清空我的浏览历史
   * DELETE /browse-history
   */
  clear() {
    return client.delete<Result<void>>('/browse-history');
  },
};
