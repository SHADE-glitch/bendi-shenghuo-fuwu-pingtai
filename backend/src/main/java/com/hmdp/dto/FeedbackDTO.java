package com.hmdp.dto;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 意见反馈列表项 DTO
 */
@Data
public class FeedbackDTO {

    /** 反馈id */
    private Long id;

    /** 反馈内容 */
    private String content;

    /** 联系方式 */
    private String contact;

    /** 类型：0-其他 1-功能异常 2-体验建议 */
    private Integer type;

    /** 状态：0-待处理 1-已处理 */
    private Integer status;

    /** 提交时间 */
    private Timestamp createTime;
}
