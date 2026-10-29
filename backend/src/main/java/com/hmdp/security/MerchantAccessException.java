package com.hmdp.security;

import com.hmdp.dto.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * 商家权限拒绝异常
 * <p>
 * 由 {@link MerchantShopGuard} 在归属校验失败时抛出，携带业务错误码与期望的
 * HTTP 状态码，交由 {@code WebExceptionAdvice} 统一转换为项目标准 {@code Result} 响应。
 * </p>
 * <p>
 * 之所以需要携带 HTTP 状态码：本项目的「业务错误」惯例是 HTTP 200 + errorCode
 * （如 40001 店铺不存在），而「权限拒绝」需要 401/403 才能被前端与调用方正确区分。
 * 现有 {@code BusinessException} 只能表达前者，故此处单独携带状态码，
 * 不改变既有异常分支的语义。
 * </p>
 */
public class MerchantAccessException extends RuntimeException {

    private final ErrorCode errorCode;

    private final HttpStatus status;

    /**
     * @param errorCode 业务错误码（如 {@code SHOP_NOT_OWNED}）
     * @param status    期望返回的 HTTP 状态码（如 {@code 403}）
     */
    public MerchantAccessException(ErrorCode errorCode, HttpStatus status) {
        super(errorCode.getMsg());
        this.errorCode = errorCode;
        this.status = status;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
