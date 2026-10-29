package com.hmdp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发布商铺评价请求
 */
@Data
public class ShopCommentCreateRequest {

    /** 店铺id */
    @NotNull(message = "店铺ID不能为空")
    private Long shopId;

    /** 评价内容 */
    @NotBlank(message = "评价内容不能为空")
    @Size(max = 1024, message = "评价内容最长1024个字符")
    private String content;

    /** 评分 1-5 */
    @NotNull(message = "评分不能为空")
    @Min(value = 1, message = "评分最低1分")
    @Max(value = 5, message = "评分最高5分")
    private Integer rating;
}
