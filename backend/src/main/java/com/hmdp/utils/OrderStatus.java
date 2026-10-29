package com.hmdp.utils;

import java.util.List;

/**
 * 订单状态常量类
 * <p>
 * 集中管理优惠券订单的状态值（对应数据库 tb_voucher_order.status 字段），
 * 避免在业务代码中散落魔法数字。
 * </p>
 */
public final class OrderStatus {

    private OrderStatus() {
    }

    /** 订单不存在（查询占位值，非数据库状态） */
    public static final int NOT_FOUND = -1;

    /** 待支付（领取成功，需在"我的优惠券"中完成支付） */
    public static final int PENDING_PAYMENT = 1;

    /** 已支付（待使用） */
    public static final int PAID = 2;

    /** 已使用 */
    public static final int USED = 3;

    /** 已取消 */
    public static final int CANCELLED = 4;

    /** 已过期 */
    public static final int EXPIRED = 5;

    /** 已退款 */
    public static final int REFUNDED = 6;

    /**
     * 释放「一人一单」唯一名额的终态集合（P1-4）。
     * <p>
     * 取消 / 退款后同一用户应能对同一张券再次下单：库存与 Redis 占位在取消/退款时
     * 已回补（{@code rollbackStock}），若唯一名额仍被终态订单占着，用户再次购买会
     * 拿到 {@code ORDER_DUPLICATE}，与「取消后可重新购买」的产品语义矛盾。
     * </p>
     * <p>
     * 物理兜底见 {@code V17__Active_Only_One_Order_Unique.sql}：唯一索引从
     * {@code (user_id, voucher_id)} 收敛为生成列 {@code active_voucher_id}
     * （本集合内的状态为 NULL，MySQL 唯一索引允许多个 NULL）。
     * </p>
     * <p>
     * EXPIRED 刻意不在此列：过期单不回补 Redis 占位（见
     * {@code settleExpiredPendingOrders} 注释），放开 DB 名额只会让用户得到
     * Lua 层的 {@code ORDER_DUPLICATE}，两种错误口径分裂；保持与 Redis 一致。
     * </p>
     */
    public static final List<Integer> UNIQUENESS_RELEASED_STATUSES = List.of(CANCELLED, REFUNDED);
}
