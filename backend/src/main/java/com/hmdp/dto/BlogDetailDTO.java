package com.hmdp.dto;

import lombok.Data;

import java.sql.Timestamp;
import java.util.List;

/**
 * 博客详情 DTO（包含评论列表）
 */
@Data
public class BlogDetailDTO {
    private Long id;
    private Long shopId;
    private Long userId;
    private String title;
    private List<String> images;
    private String content;
    private Integer liked;
    private Integer comments;
    private Timestamp createTime;
    
    // 关联数据
    private UserDTO author;
    private String shopName;

    /** 当前登录用户是否已点赞该笔记 */
    private Boolean likedByMe;

    // 评论列表（最新 10 条）
    private List<String> commentContents;
}
