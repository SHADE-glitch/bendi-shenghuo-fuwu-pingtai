package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 优惠券订单详情 DTO
 */
@Data
public class VoucherOrderDetailDTO {
    /** 订单 ID */
    private Long orderId;
    /** 用户 ID */
    private Long userId;
    /** 优惠券 ID */
    private Long voucherId;
    /** 订单状态 */
    private Integer status;
    /** 支付方式 */
    private Integer payType;
    /** 下单时间 */
    private LocalDateTime createTime;
    /** 支付时间 */
    private LocalDateTime payTime;
    /** 核销时间 */
    private LocalDateTime useTime;
    /** 退款时间 */
    private LocalDateTime refundTime;

    /** 优惠券标题 */
    private String voucherTitle;
    /** 优惠券副标题 */
    private String voucherSubTitle;
    /** 使用规则 */
    private String voucherRules;
    /** 支付金额（分） */
    private Long payValue;
    /** 抵扣金额（分） */
    private Long actualValue;
    /** 有效期开始时间（秒杀券） */
    private LocalDateTime beginTime;
    /** 有效期结束时间（秒杀券） */
    private LocalDateTime endTime;

    /** 所属店铺 ID */
    private Long shopId;
    /** 所属店铺名称 */
    private String shopName;
    /** 所属店铺图片 */
    private String shopImage;
}
