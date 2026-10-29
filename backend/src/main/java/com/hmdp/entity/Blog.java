package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

/**
 * 探店博客实体
 * <p>对应数据库表：tb_blog</p>
 */
@Data
@TableName("tb_blog")
public class Blog {
    /** 主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    /** 商户 id */
    @TableField("shop_id")
    private Long shopId;
    
    /** 用户 id */
    @TableField("user_id")
    private Long userId;
    
    /** 标题 */
    @TableField("title")
    private String title;
    
    /** 探店的照片，最多 9 张，JSON 数组存储 */
    @TableField("images")
    private String images;
    
    /** 探店的文字描述 */
    @TableField("content")
    private String content;
    
    /** 点赞数量 */
    @TableField("liked")
    private Integer liked;
    
    /** 评论数量 */
    @TableField("comments")
    private Integer comments;
    
    /** 创建时间 */
    @TableField("create_time")
    private Timestamp createTime;
    
    /** 更新时间 */
    @TableField("update_time")
    private Timestamp updateTime;
}
