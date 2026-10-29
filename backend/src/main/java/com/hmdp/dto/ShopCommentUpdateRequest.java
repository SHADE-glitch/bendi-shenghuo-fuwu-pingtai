package com.hmdp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 编辑商铺评价请求
 * <p>
 * 编辑只能修改内容与评分，不允许转移评价所属店铺，因此不包含 shopId。
 * </p>
 */
@Data
public class ShopCommentUpdateRequest {

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
