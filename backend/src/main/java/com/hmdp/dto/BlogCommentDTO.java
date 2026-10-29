package com.hmdp.dto;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 笔记评论列表项 DTO
 */
@Data
public class BlogCommentDTO {

    /** 评论id */
    private Long id;

    /** 笔记id */
    private Long blogId;

    /** 笔记标题（「我的评论」列表使用） */
    private String blogTitle;

    /** 评论用户id */
    private Long userId;

    /** 评论用户昵称 */
    private String nickName;

    /** 评论用户头像 */
    private String icon;

    /** 关联的一级评论id，0 表示一级评论 */
    private Long parentId;

    /** 回复的评论id */
    private Long answerId;

    /** 被回复者昵称 */
    private String answerNickName;

    /** 评论内容 */
    private String content;

    /** 评论时间 */
    private Timestamp createTime;
}
