import client from './client';
import { Result, MessageListDTO } from './types';

export interface MessageQuery {
  type?: number;
  isRead?: number;
  current?: number;
  size?: number;
}

/**
 * 对应后端 MessageController（消息通知）
 */
export const messageService = {
  /**
   * 分页查询消息列表
   * GET /messages/list?type=&isRead=&current=&size=
   */
  queryMessages(params: MessageQuery = {}) {
    return client.get<Result<MessageListDTO[]>>('/messages/list', { params });
  },

  /**
   * 标记消息为已读
   * POST /messages/read/{messageId}
   */
  markAsRead(messageId: number | string) {
    return client.post<Result<void>>(`/messages/read/${messageId}`);
  },

  /**
   * 将全部未读消息标记为已读
   * POST /messages/read-all
   */
  readAll() {
    return client.post<Result<number>>('/messages/read-all');
  },

  /**
   * 获取未读消息数量
   * GET /messages/unread/count
   */
  getUnreadCount() {
    return client.get<Result<number>>('/messages/unread/count');
  },
};
