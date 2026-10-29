package com.hmdp.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 记录浏览历史请求
 */
@Data
public class BrowseHistoryCreateRequest {

    /** 目标类型：1-店铺 2-笔记 */
    @NotNull(message = "目标类型不能为空")
    private Integer targetType;

    /** 目标id */
    @NotNull(message = "目标ID不能为空")
    private Long targetId;
}
