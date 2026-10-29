import client from './client';
import { FeedbackCreateRequest, FeedbackDTO, Result } from './types';

/**
 * 对应后端 FeedbackController（意见反馈）
 */
export const feedbackService = {
  /**
   * 提交反馈
   * POST /feedbacks
   */
  submit(payload: FeedbackCreateRequest) {
    return client.post<Result<number | string>>('/feedbacks', payload);
  },

  /**
   * 分页查询我的反馈
   * GET /feedbacks/mine?current=&size=
   */
  mine(current = 1, size = 10) {
    return client.get<Result<FeedbackDTO[]>>('/feedbacks/mine', {
      params: { current, size },
    });
  },

  /**
   * 删除我的反馈（仅本人）
   * DELETE /feedbacks/{feedbackId}
   */
  remove(feedbackId: number | string) {
    return client.delete<Result<void>>(`/feedbacks/${feedbackId}`);
  },
};
