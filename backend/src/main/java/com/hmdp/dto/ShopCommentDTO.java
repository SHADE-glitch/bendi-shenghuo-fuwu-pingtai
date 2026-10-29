package com.hmdp.dto;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 商铺评价列表项 DTO
 */
@Data
public class ShopCommentDTO {

    /** 评价id */
    private Long id;

    /** 店铺id */
    private Long shopId;

    /** 店铺名称（「我的评价」列表使用） */
    private String shopName;

    /** 评价用户id */
    private Long userId;

    /** 评价用户昵称 */
    private String nickName;

    /** 评价用户头像 */
    private String icon;

    /** 评价内容 */
    private String content;

    /** 评分 1-5 */
    private Integer rating;

    /** 评价时间 */
    private Timestamp createTime;
}
