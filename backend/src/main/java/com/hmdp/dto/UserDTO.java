package com.hmdp.dto;

import lombok.Data;

/**
 * 登录用户信息 DTO
 * <p>
 * 存储于 Token/Session 中的用户精简信息，仅包含用户 ID、昵称和头像，
 * 用于后续请求的身份识别与页面展示，避免频繁查询完整用户表。
 * </p>
 */
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;

    /**
     * 用户角色：0=普通用户 1=商家（随登录态写入 Redis，供 ThreadLocal 鉴权使用）
     */
    private Integer role;
}
