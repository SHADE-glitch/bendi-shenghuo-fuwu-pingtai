package com.hmdp.dto;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 关注 / 粉丝列表项 DTO
 */
@Data
public class FollowUserDTO {

    /** 对方用户id */
    private Long userId;

    /** 对方昵称 */
    private String nickName;

    /** 对方头像 */
    private String icon;

    /** 当前登录用户是否已关注该用户 */
    private Boolean followed;

    /** 关系建立时间（关注时间 / 成为粉丝时间） */
    private Timestamp createTime;
}
