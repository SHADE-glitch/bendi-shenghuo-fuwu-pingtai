package com.hmdp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交意见反馈请求
 */
@Data
public class FeedbackCreateRequest {

    /** 反馈内容 */
    @NotBlank(message = "反馈内容不能为空")
    @Size(max = 1024, message = "反馈内容最长1024个字符")
    private String content;

    /** 联系方式（可选） */
    @Size(max = 128, message = "联系方式最长128个字符")
    private String contact;

    /** 类型：0-其他 1-功能异常 2-体验建议 */
    @Min(value = 0, message = "无效的反馈类型")
    @Max(value = 2, message = "无效的反馈类型")
    private Integer type;
}
