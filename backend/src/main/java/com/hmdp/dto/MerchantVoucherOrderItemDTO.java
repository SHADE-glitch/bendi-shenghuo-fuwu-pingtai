package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商家订单列表项 DTO（Phase 3.4-B.1）
 * <p>
 * 用于商家侧「我店铺的订单」列表展示：把一条 {@code tb_voucher_order} 记录
 * 与其关联的 {@code tb_voucher}、{@code tb_shop} 的关键字段组合成一行列表项。
 * </p>
 *
 * <h3>为什么需要独立 DTO（不复用 MyVoucherItemDTO / VoucherOrderDetailDTO）</h3>
 * <ul>
 *   <li>{@code MyVoucherItemDTO} 是<b>消费者</b>「我的优惠券」视图，缺少商家履约所需的
 *       {@link #userId}（买家）与 {@link #useTime}（核销时间）；</li>
 *   <li>{@code VoucherOrderDetailDTO} 是<b>详情</b>形状，字段偏重，不适合作为列表项；</li>
 *   <li>消费者视图与商家视图属两个不同的业务边界，复用同一 DTO 会使任一方的字段变更
 *       牵连另一方。</li>
 * </ul>
 *
 * <h3>字段来源</h3>
 * <ul>
 *   <li>订单维度（{@code orderId}/{@code status}/{@code createTime}/{@code payTime}/
 *       {@code useTime}/{@code userId}）取自 {@code tb_voucher_order}；</li>
 *   <li>券维度（{@code voucherId}/{@code voucherTitle}/{@code payValue}/
 *       {@code actualValue}）取自 {@code tb_voucher}；</li>
 *   <li>店铺维度（{@code shopId}/{@code shopName}）取自 {@code tb_shop}。</li>
 * </ul>
 *
 * <h3>序列化</h3>
 * <p>
 * 本项目 {@code JacksonConfig} 将 {@code Long}/{@code long} 全局序列化为字符串
 * （避免雪花 ID 超出 JS 安全整数范围），因此 {@link #orderId}、{@link #userId}、
 * {@link #voucherId}、{@link #shopId} 以及金额字段 {@link #payValue}/
 * {@link #actualValue} 在 JSON 中均为字符串。
 * </p>
 *
 * <p>
 * 本类<b>只承载数据</b>，不含任何业务、查询、权限或数据库逻辑。
 * </p>
 */
@Data
public class MerchantVoucherOrderItemDTO {

    // ------------------------------------------------------------------
    // 订单维度（tb_voucher_order）
    // ------------------------------------------------------------------

    /** 订单 ID（供商家后续调用核销接口） */
    private Long orderId;

    /** 订单状态：1待支付；2已支付；3已核销；4已取消；5已过期；6已退款 */
    private Integer status;

    /** 下单时间 */
    private LocalDateTime createTime;

    /** 支付时间（未支付为 null） */
    private LocalDateTime payTime;

    /** 核销时间（未核销为 null） */
    private LocalDateTime useTime;

    /** 买家用户 ID（商家履约识别用） */
    private Long userId;

    // ------------------------------------------------------------------
    // 券维度（tb_voucher）
    // ------------------------------------------------------------------

    /** 优惠券 ID */
    private Long voucherId;

    /** 优惠券标题 */
    private String voucherTitle;

    /** 支付金额（分） */
    private Long payValue;

    /** 抵扣金额（分） */
    private Long actualValue;

    // ------------------------------------------------------------------
    // 店铺维度（tb_shop）
    // ------------------------------------------------------------------

    /** 所属店铺 ID */
    private Long shopId;

    /** 所属店铺名称 */
    private String shopName;
}
