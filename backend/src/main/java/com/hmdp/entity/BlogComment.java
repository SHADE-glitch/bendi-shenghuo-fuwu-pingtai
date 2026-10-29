package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.sql.Timestamp;

/**
 * 博客评论实体
 * <p>对应数据库表：tb_blog_comments</p>
 */
@Data
@TableName("tb_blog_comments")
public class BlogComment {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    @TableField("user_id")
    private Long userId;
    
    @TableField("blog_id")
    private Long blogId;
    
    @TableField("parent_id")
    private Long parentId;
    
    @TableField("answer_id")
    private Long answerId;
    
    @TableField("content")
    private String content;
    
    @TableField("liked")
    private Integer liked;
    
    @TableField("status")
    private Integer status;
    
    @TableField("create_time")
    private Timestamp createTime;
    
    @TableField("update_time")
    private Timestamp updateTime;
}
