package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.UpdateUserInfoDTO;
import com.hmdp.entity.UserInfo;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 用户信息服务接口
 * <p>提供用户扩展信息的查询与更新能力。</p>
 */
public interface IUserInfoService extends IService<UserInfo> {

    /**
     * 更新当前登录用户的扩展信息，并同步刷新 Redis 登录缓存中的昵称/头像
     *
     * @param dto   用户信息更新数据
     * @param token 当前登录凭证（用于定位 Redis 登录缓存）
     * @return 更新结果
     */
    Result updateUserInfo(UpdateUserInfoDTO dto, String token);

    /**
     * 根据用户 ID 查询用户信息详情（剔除创建/更新时间字段）
     *
     * @param userId 用户 ID
     * @return 用户详情信息结果
     */
    Result queryUserInfo(Long userId);
}