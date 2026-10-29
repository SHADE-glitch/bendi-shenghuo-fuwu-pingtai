package com.hmdp.utils;

/**
 * 正则表达式常量
 * <p>
 * 定义项目中常用的校验正则表达式，包括手机号、邮箱、密码、验证码等格式。
 * 本类为抽象类，防止被意外实例化。
 * </p>
 */
public abstract class RegexPatterns {
    /**
     * 中国大陆手机号正则表达式
     * <p>
     * 支持 13x、18x、14x、15x、16x、17x、19x 等号段。
     * </p>
     */
    public static final String PHONE_REGEX = "^1([38][0-9]|4[579]|5[0-3,5-9]|6[6]|7[0135678]|9[89])\\d{8}$";
    /**
     * 电子邮箱地址正则表达式
     * <p>
     * 允许字母、数字、下划线、连字符，支持多级域名。
     * </p>
     */
    public static final String EMAIL_REGEX = "^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$";
    /**
     * 密码正则表达式：4~32 位的字母、数字或下划线
     */
    public static final String PASSWORD_REGEX = "^\\w{4,32}$";
    /**
     * 验证码正则表达式：6 位字母或数字组合
     */
    public static final String VERIFY_CODE_REGEX = "^[a-zA-Z\\d]{6}$";

}
