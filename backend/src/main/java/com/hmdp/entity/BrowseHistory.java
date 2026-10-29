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
 * 浏览历史实体类
 * <p>对应数据库表：tb_browse_history，按 (user_id, target_type, target_id) 唯一去重</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_browse_history")
public class BrowseHistory implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户id
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 目标类型：1-店铺 2-笔记
     */
    @TableField("target_type")
    private Integer targetType;

    /**
     * 目标id
     */
    @TableField("target_id")
    private Long targetId;

    /**
     * 首次浏览时间
     */
    @TableField("create_time")
    private Timestamp createTime;

    /**
     * 最近浏览时间
     */
    @TableField("update_time")
    private Timestamp updateTime;
}
