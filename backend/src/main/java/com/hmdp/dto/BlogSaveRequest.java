package com.hmdp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发布博客请求 DTO
 */
@Data
public class BlogSaveRequest {
    @NotBlank(message = "标题不能为空")
    private String title;
    
    @NotBlank(message = "内容不能为空")
    @Size(max = 2048, message = "内容不能超过 2048 个字符")
    private String content;
    
    private String[] images;
    
    @NotNull(message = "店铺ID不能为空")
    private Long shopId;
}
