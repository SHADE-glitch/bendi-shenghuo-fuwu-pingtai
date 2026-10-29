package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 关注关系服务接口
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注指定用户（幂等，重复关注不报错、不重复计数）
     */
    Result follow(Long targetUserId);

    /**
     * 取消关注指定用户（幂等）
     */
    Result unfollow(Long targetUserId);

    /**
     * 分页查询当前用户关注的人
     */
    Result queryFollowing(Integer current, Integer size);

    /**
     * 分页查询当前用户的粉丝
     */
    Result queryFollowers(Integer current, Integer size);

    /**
     * 判断 userId 是否已关注 targetUserId
     * <p>用于「他人主页」展示关注按钮状态；userId 为空或等于 targetUserId 时返回 false。</p>
     *
     * @param userId       关注者id
     * @param targetUserId 被关注者id
     * @return 是否已关注
     */
    boolean hasFollowed(Long userId, Long targetUserId);
}
