import client from './client';
import { Result, FollowUserDTO } from './types';

/**
 * 对应后端 FollowController（关注 / 粉丝）
 */
export const followService = {
  /**
   * 关注指定用户（幂等）
   * POST /follows/{userId}
   */
  follow(userId: number | string) {
    return client.post<Result<void>>(`/follows/${userId}`);
  },

  /**
   * 取消关注指定用户（幂等）
   * DELETE /follows/{userId}
   */
  unfollow(userId: number | string) {
    return client.delete<Result<void>>(`/follows/${userId}`);
  },

  /**
   * 分页查询我关注的人
   * GET /follows/following?current=&size=
   */
  following(current = 1, size = 10) {
    return client.get<Result<FollowUserDTO[]>>('/follows/following', {
      params: { current, size },
    });
  },

  /**
   * 分页查询我的粉丝
   * GET /follows/followers?current=&size=
   */
  followers(current = 1, size = 10) {
    return client.get<Result<FollowUserDTO[]>>('/follows/followers', {
      params: { current, size },
    });
  },
};
