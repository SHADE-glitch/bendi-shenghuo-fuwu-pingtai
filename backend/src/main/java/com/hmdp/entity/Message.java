package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.sql.Timestamp;

/**
 * 消息通知实体
 * <p>对应数据库表：tb_message</p>
 */
@Data
@TableName("tb_message")
public class Message {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    
    @TableField("user_id")
    private Long userId;

    /**
     * 发送者用户id（关注/点赞/评论的触发者），系统通知可为空
     */
    @TableField("sender_id")
    private Long senderId;

    @TableField("content")
    private String content;
    
    /**
     * 消息类型：
     * 1-点赞
     * 2-关注
     * 3-评论
     */
    @TableField("type")
    private Integer type;
    
    @TableField("related_id")
    private Long relatedId;
    
    /**
     * 是否已读：
     * 0-未读
     * 1-已读
     */
    @TableField("is_read")
    private Integer isRead;
    
    @TableField("create_time")
    private Timestamp createTime;
}
