package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * 用户服务接口
 * <p>提供用户登录、验证码发送、签到等核心用户业务能力。</p>
 */
public interface IUserService extends IService<User> {

    /**
     * 向指定手机号发送登录验证码
     *
     * @param phone   手机号
     * @param session HTTP 会话
     * @return 发送结果
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 开发/测试辅助：设置固定验证码，便于本地接口联调
     *
     * @param phone 手机号
     * @param code  验证码
     * @return 设置结果
     */
    Result mockCode(String phone, String code);

    /**
     * 用户登录（验证码校验并返回登录凭证）
     *
     * @param loginForm 登录表单数据
     * @param session   HTTP 会话
     * @return 登录结果
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 用户登出（删除 Redis 中的登录缓存）
     *
     * @param token 登录凭证
     * @return 登出结果
     */
    Result logout(String token);

    /**
     * 根据用户 ID 查询用户基本信息（转换为 DTO 返回）
     *
     * @param userId 用户 ID
     * @return 用户 DTO 结果
     */
    Result queryUserDTO(Long userId);

    /**
     * 用户每日签到
     *
     * @return 签到结果
     */
    Result sign();

    /**
     * 统计当前用户连续签到天数
     *
     * @return 连续签到天数结果
     */
    Result signCount();

    /**
     * 查询当前用户今日是否已签到
     *
     * @return 今日签到状态结果
     */
    Result isSignedToday();
}
