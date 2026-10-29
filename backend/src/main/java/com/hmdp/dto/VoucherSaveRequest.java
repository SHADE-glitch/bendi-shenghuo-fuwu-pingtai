package com.hmdp.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/**
 * 新增优惠券请求参数 DTO
 * <p>
 * 接收新增普通券/秒杀券时提交的优惠券信息，携带 {@code @Valid} 校验注解，
 * 在进入 Controller 前自动完成参数合法性验证。
 * 秒杀券场景额外携带 stock、beginTime、endTime 字段。
 * </p>
 */
@Data
public class VoucherSaveRequest {

    /**
     * 商铺id（必填：券必须归属到某家店铺，缺失由 @Valid 在进入业务层前拦截）
     */
    @NotNull(message = "店铺id不能为空")
    private Long shopId;

    /**
     * 代金券标题
     */
    @NotBlank(message = "代金券标题不能为空")
    private String title;

    /**
     * 副标题
     */
    private String subTitle;

    /**
     * 使用规则
     */
    private String rules;

    /**
     * 支付金额
     */
    @NotNull(message = "支付金额不能为空")
    private Long payValue;

    /**
     * 抵扣金额
     */
    @NotNull(message = "抵扣金额不能为空")
    private Long actualValue;

    /**
     * 优惠券类型
     */
    @NotNull(message = "优惠券类型不能为空")
    private Integer type;

    /**
     * 优惠券状态
     */
    private Integer status;

    /**
     * 库存（仅秒杀券）
     */
    private Integer stock;

    /**
     * 生效时间（仅秒杀券）
     */
    private LocalDateTime beginTime;

    /**
     * 失效时间（仅秒杀券）
     */
    private LocalDateTime endTime;
}
