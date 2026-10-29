package com.hmdp.dto;

import lombok.Data;

/**
 * 商铺评价汇总 DTO
 */
@Data
public class ShopCommentSummaryDTO {

    /** 评价总数 */
    private Long count;

    /** 平均评分（无评价时为 null） */
    private Double avgRating;
}
