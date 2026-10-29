package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码枚举
 * <p>
 * 定义系统所有业务场景的错误码与中文描述，按模块分段：
 * {@code 1xxxx} 用户/认证、{@code 2xxxx} 内容/笔记、
 * {@code 3xxxx} 交易/库存、{@code 4xxxx} 店铺、{@code 99999} 系统级错误。
 * 每个错误码包含唯一编码和可读描述，配合 {@link Result} 统一返回给前端。
 * </p>
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    SUCCESS("00000", "操作成功"),

    // 1xxxx — 用户 / 认证
    PHONE_REQUIRED("10000", "手机号不能为空"),
    AUTH_REQUIRED("10001", "用户未登录"),
    PHONE_INVALID("10002", "手机号格式错误"),
    LOGIN_CODE_INVALID("10003", "验证码错误"),
    LOGIN_CODE_EXPIRED("10004", "验证码过期"),
    FOLLOW_SELF_NOT_ALLOWED("10005", "不能关注自己"),
    CODE_SEND_TOO_FREQUENT("10006", "验证码发送太频繁，请稍后再试"),
    FOLLOW_INVALID_TARGET("10007", "关注目标不存在"),
    PARAM_INVALID("10008", "参数校验失败"),
    IDEMPOTENCY_REPLAY("10009", "请求重复，请勿重复提交"),
    LOGIN_CODE_ATTEMPTS_EXCEEDED("10010", "验证码错误次数过多，请重新获取验证码"),

    // 2xxxx — 内容 / 笔记
    BLOG_NOT_FOUND("20001", "博客不存在"),
    BLOG_COMMENT_NOT_FOUND("20002", "评论不存在"),

    // 3xxxx — 交易 / 库存
    VOUCHER_NOT_FOUND("30000", "优惠券不存在"),
    STOCK_NOT_ENOUGH("30001", "库存不足"),
    ORDER_DUPLICATE("30002", "您已购买过该优惠券，无法重复下单"),
    ORDER_CREATE_FAILED("30003", "订单创建失败，请稍后重试"),
    ORDER_CONFLICT("30004", "请求处理中，请稍候"),
    SECKILL_NOT_STARTED("30005", "秒杀活动尚未开始"),
    SECKILL_ENDED("30006", "秒杀活动已结束"),
    VOUCHER_TYPE_MISMATCH("30007", "优惠券类型不匹配"),
    ORDER_NOT_FOUND("30008", "订单不存在"),
    ORDER_STATUS_INVALID("30009", "当前订单状态不允许该操作"),
    VOUCHER_EXPIRED("30010", "优惠券已过期，无法支付"),

    // 4xxxx — 店铺 / 商家
    SHOP_NOT_FOUND("40001", "店铺不存在"),
    SHOP_COMMENT_NOT_FOUND("40002", "评价不存在"),
    MERCHANT_ONLY("40003", "仅商家可操作"),
    SHOP_NOT_OWNED("40004", "无权操作该店铺"),

    // 99999 — 系统
    SYSTEM_ERROR("99999", "系统繁忙，请稍后再试");

    private final String code;
    private final String msg;
}
