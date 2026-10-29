package com.hmdp.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改优惠券请求参数 DTO（Merchant 侧，Phase 3.2）
 * <p>
 * 采用<b>白名单</b>设计：只声明商家真正可以修改的字段。
 * 其余字段因<b>不在本 DTO 中</b>而无法通过请求体影响：
 * <ul>
 *   <li>{@code id}：由路径参数 {@code /vouchers/{voucherId}} 决定；</li>
 *   <li>{@code shopId}：券的归属店铺，修改它等于把券「搬」到别人店铺；</li>
 *   <li>{@code type}：普通券/秒杀券的类型契约，改它会产生结构不完整数据
 *       （{@code type=1} 却无 {@code tb_seckill_voucher} 行与 Redis 库存）；</li>
 *   <li>{@code stock}/{@code beginTime}/{@code endTime}：秒杀生命周期的权威值在
 *       {@code tb_seckill_voucher} 与 Redis，改 {@code tb_voucher} 无法表达且会造成不一致；</li>
 *   <li>{@code createTime}/{@code updateTime}：由数据库维护。</li>
 * </ul>
 * 项目未开启 {@code FAIL_ON_UNKNOWN_PROPERTIES}（Spring Boot 默认关闭），
 * 因此客户端若在请求体中夹带 {@code shopId}/{@code type} 等字段会被<b>静默忽略</b>，
 * 既不会报错也不会生效。
 * </p>
 */
@Data
public class VoucherUpdateRequest {

    /**
     * 代金券标题（必填：{@code tb_voucher.title} 为 NOT NULL，
     * 同时保证 {@code updateById} 至少有一个待更新字段）
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
    private Long payValue;

    /**
     * 抵扣金额
     */
    private Long actualValue;

    /**
     * 状态：1 上架 / 2 下架 / 3 过期（取值由 Service 校验）
     */
    private Integer status;
}
