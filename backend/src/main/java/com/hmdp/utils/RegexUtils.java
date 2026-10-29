package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 正则校验工具类
 * <p>
 * 提供手机号、邮箱、验证码等常见格式的校验方法，
 * 底层复用 {@link RegexPatterns} 中定义的正则表达式常量。
 * 方法命名采用 {@code isXxxInvalid} 风格，返回 true 表示格式不合法。
 * </p>
 */
public class RegexUtils {
    /**
     * 校验手机号格式是否不合法。
     *
     * @param phone 待校验的手机号字符串
     * @return true 表示格式不合法（为空或不符合正则）；false 表示格式合法
     */
    public static boolean isPhoneInvalid(String phone){
        return mismatch(phone, RegexPatterns.PHONE_REGEX);
    }
    /**
     * 校验邮箱格式是否不合法。
     *
     * @param email 待校验的邮箱地址
     * @return true 表示格式不合法（为空或不符合正则）；false 表示格式合法
     */
    public static boolean isEmailInvalid(String email){
        return mismatch(email, RegexPatterns.EMAIL_REGEX);
    }

    /**
     * 校验验证码格式是否不合法。
     *
     * @param code 待校验的验证码字符串
     * @return true 表示格式不合法（为空或不符合正则）；false 表示格式合法
     */
    public static boolean isCodeInvalid(String code){
        return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
    }

    // 校验是否不符合正则格式
    private static boolean mismatch(String str, String regex){
        if (StrUtil.isBlank(str)) {
            return true;
        }
        return !str.matches(regex);
    }
}
