package com.hmdp.utils;

/**
 * 支付方式常量类
 * <p>
 * 集中管理优惠券订单的支付方式值（对应数据库 tb_voucher_order.pay_type 字段），
 * 避免在业务代码中散落魔法数字。
 * </p>
 */
public final class PayType {

    private PayType() {
    }

    /** 余额支付 */
    public static final int BALANCE = 1;

    /** 微信支付 */
    public static final int WECHAT = 2;

    /** 支付宝支付 */
    public static final int ALIPAY = 3;
}
