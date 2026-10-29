package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.sql.Timestamp;

/**
 * 意见反馈实体类
 * <p>对应数据库表：tb_feedback</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_feedback")
public class Feedback implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 提交用户id
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 反馈内容
     */
    @TableField("content")
    private String content;

    /**
     * 联系方式（可选）
     */
    @TableField("contact")
    private String contact;

    /**
     * 类型：0-其他 1-功能异常 2-体验建议
     */
    @TableField("type")
    private Integer type;

    /**
     * 状态：0-待处理 1-已处理
     */
    @TableField("status")
    private Integer status;

    /**
     * 创建时间
     */
    @TableField("create_time")
    private Timestamp createTime;

    /**
     * 更新时间
     */
    @TableField("update_time")
    private Timestamp updateTime;
}
