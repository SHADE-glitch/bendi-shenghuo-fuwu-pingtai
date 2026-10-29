package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的优惠券列表项 DTO
 * <p>
 * 用于“我的优惠券”页面展示，组合订单与优惠券基础信息。
 * </p>
 */
@Data
public class MyVoucherItemDTO {
    /** 订单 ID */
    private Long orderId;
    /** 订单状态：1未支付；2已支付；3已核销；4已取消；5已过期；6已退款 */
    private Integer orderStatus;
    /** 下单时间 */
    private LocalDateTime orderCreateTime;
    /** 支付时间 */
    private LocalDateTime payTime;

    /** 优惠券 ID */
    private Long voucherId;
    /** 代金券标题 */
    private String title;
    /** 副标题 */
    private String subTitle;
    /** 使用规则 */
    private String rules;
    /** 支付金额（分） */
    private Long payValue;
    /** 抵扣金额（分） */
    private Long actualValue;
    /** 0普通券；1秒杀券 */
    private Integer type;
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
