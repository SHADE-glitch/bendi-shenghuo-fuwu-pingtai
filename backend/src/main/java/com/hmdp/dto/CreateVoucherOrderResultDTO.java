package com.hmdp.dto;

import lombok.Data;

/**
 * 创建优惠券订单结果 DTO
 * <p>
 * 用于返回秒杀优惠券下单操作的结果，包含订单 ID、优惠券 ID、订单状态和重复下单拦截状态。
 * </p>
 */
@Data
public class CreateVoucherOrderResultDTO {
    private Long orderId;
    private Long voucherId;
    private Integer orderStatus;
    private Boolean duplicateBlocked;
}
