package com.hmdp.dto;

import lombok.Data;
import org.slf4j.MDC;

import java.util.List;

/**
 * 统一响应结果封装
 * <p>
 * 所有 Controller 接口统一返回此结构，包含成功标识、错误码、错误信息、
 * 响应数据、分页总数及追踪 ID。{@code traceId} 从 MDC 中自动获取，
 * 便于全链路日志追踪。提供 {@code ok} / {@code fail} 静态工厂方法
 * 构建成功或失败响应。
 * </p>
 *
 * @param <T> 响应数据类型
 */
@Data
public class Result<T> {
    private Boolean success;
    private String errorCode;
    private String errorMsg;
    private T data;
    private Long total;
    private String traceId;

    public Result() {
        this.traceId = MDC.get("traceId");
    }

    public Result(Boolean success, String errorMsg, T data, Long total) {
        this.success = success;
        this.errorMsg = errorMsg;
        this.data = data;
        this.total = total;
        this.traceId = MDC.get("traceId");
        this.errorCode = success ? null : ErrorCode.SYSTEM_ERROR.getCode();
    }

    public static <T> Result<T> ok() {
        return new Result<>(true, null, null, null);
    }
    public static <T> Result<T> ok(T data) {
        return new Result<>(true, null, data, null);
    }
    public static <T> Result<T> ok(List<T> data, Long total) {
        return new Result<>(true, null, (T) data, total);
    }
    
    public static <T> Result<T> fail(ErrorCode err) {
        Result<T> result = new Result<>(false, err.getMsg(), null, null);
        result.setErrorCode(err.getCode());
        return result;
    }

    public static <T> Result<T> fail(ErrorCode err, T data) {
        Result<T> result = new Result<>(false, err.getMsg(), data, null);
        result.setErrorCode(err.getCode());
        return result;
    }

    public static <T> Result<T> fail(String errorMsg) {
        return new Result<>(false, errorMsg, null, null);
    }

    public static <T> Result<T> fail(String errorCode, String errorMsg) {
        Result<T> result = new Result<>(false, errorMsg, null, null);
        result.setErrorCode(errorCode);
        return result;
    }

    public static <T> Result<T> fail(String errorCode, String errorMsg, T data) {
        Result<T> result = new Result<>(false, errorMsg, data, null);
        result.setErrorCode(errorCode);
        return result;
    }
}
