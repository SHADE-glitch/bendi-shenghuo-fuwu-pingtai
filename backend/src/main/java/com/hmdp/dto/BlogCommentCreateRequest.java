package com.hmdp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发布笔记评论请求
 */
@Data
public class BlogCommentCreateRequest {

    /** 评论内容 */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 255, message = "评论内容最长255个字符")
    private String content;

    /** 关联的一级评论id（0 或空表示一级评论） */
    private Long parentId;

    /** 回复的评论id（回复某条评论时填写） */
    private Long answerId;
}
