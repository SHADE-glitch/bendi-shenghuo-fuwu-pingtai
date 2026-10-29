package com.hmdp.dto;

import lombok.Data;

/**
 * 登录响应结果 DTO
 * <p>
 * 登录成功后返回给前端的数据，包含访问令牌（token）和当前登录用户的精简信息。
 * 前端将 token 写入请求 header 或 localStorage，用于后续鉴权。
 * </p>
 */
@Data
public class LoginResultDTO {
    /**
     * 登录 token（写入 header: authorization / 前端 localStorage）
     */
    private String token;

    /**
     * 当前登录用户
     */
    private UserDTO user;
}
