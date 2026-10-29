package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.sql.Timestamp;

/**
 * 关注关系实体类
 * <p>
 * 对应数据库表 tb_follow，记录「谁关注了谁」。使用逻辑删除，
 * 配合唯一索引 uk_user_follow(user_id, follow_user_id) 保证同一对关系唯一。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_follow")
public class Follow implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关注者用户id
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 被关注者用户id
     */
    @TableField("follow_user_id")
    private Long followUserId;

    /**
     * 关注时间
     */
    @TableField("create_time")
    private Timestamp createTime;

    /**
     * 逻辑删除：0-正常 1-已取消关注
     */
    @TableLogic
    @TableField("is_deleted")
    private Integer isDeleted;
}
