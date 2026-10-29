package com.hmdp.dto;

import lombok.Data;

/**
 * 他人主页聚合 DTO
 * <p>
 * 聚合用户基础资料、统计信息与「当前登录用户是否已关注 TA」，
 * 供 {@code GET /users/{userId}/profile} 一次性返回，避免前端多次请求。
 * </p>
 */
@Data
public class UserProfileDTO {

    /** 用户id */
    private Long id;

    /** 昵称 */
    private String nickName;

    /** 头像 */
    private String icon;

    /** 个人介绍 */
    private String introduce;

    /** 性别，0-男 1-女 */
    private Boolean gender;

    /** 积分 */
    private Integer credits;

    /** 会员等级 0-9，0 表示未开通会员 */
    private Integer level;

    /** 关注数 */
    private Integer followee;

    /** 粉丝数 */
    private Integer fans;

    /** 该用户的笔记数量 */
    private Long blogCount;

    /** 当前登录用户是否已关注该用户（未登录恒为 false） */
    private Boolean followedByMe;
}
