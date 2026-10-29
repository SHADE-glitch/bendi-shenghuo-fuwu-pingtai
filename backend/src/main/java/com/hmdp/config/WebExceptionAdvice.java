package com.hmdp.config;

import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.security.MerchantAccessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器
 * <p>
 * 通过 {@link RestControllerAdvice} 统一拦截所有 Controller 层未捕获异常，
 * 将其转换为 {@link Result} 格式的标准响应。处理的异常类型包括：
 * <ul>
 *   <li>{@link BusinessException} - 业务异常，携带错误码返回给前端</li>
 *   <li>{@link MethodArgumentNotValidException} - {@code @RequestBody} 参数校验失败</li>
 *   <li>{@link ConstraintViolationException} - {@code @RequestParam} / {@code @PathVariable} 校验失败</li>
 *   <li>{@link BindException} - 表单绑定异常</li>
 *   <li>{@link RuntimeException} - 其他运行时异常，返回系统错误</li>
 *   <li>{@link Exception} - 兜底处理，记录完整堆栈</li>
 * </ul>
 * </p>
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    /**
     * 日志中允许出现的不可信字符串的最大长度
     * <p>
     * 超长即截断：异常消息里可能嵌着整个请求体，不设上限会放大日志量。
     * </p>
     */
    private static final int LOG_FIELD_MAX_LENGTH = 200;

    /**
     * 把不可信字符串转成可安全写入日志的形式
     * <p>
     * <b>为什么需要</b>：{@code HttpMessageNotReadableException.getMessage()} 会把
     * Jackson 的出错 token 原文嵌进来，而那个 token 是<b>请求体里的用户输入</b>。
     * 攻击者只要把 {@code \n} 放进一个类型不匹配的字段，日志就会被截断，
     * 从第 0 列开始伪造出独立的一条日志记录（2026-10-06 已运行时复现，见
     * {@code docs/03-evidence/audit-remediation-log.md} F-4）。
     * </p>
     * <p>
     * <b>做法</b>：CR / LF / 其余 C0 控制字符 / DEL 一律替换成可见转义
     * （{@code \r}、{@code \n}、反斜杠 + u + 四位十六进制），并做长度截断。
     * 这样日志记录永远不会被调用方输入改变行结构。
     * </p>
     *
     * @param raw 原始字符串，可为 {@code null}
     * @return 已转义、已截断的日志安全字符串
     */
    static String sanitizeForLog(String raw) {
        if (raw == null) {
            return "<null>";
        }
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), LOG_FIELD_MAX_LENGTH) + 16);
        for (int i = 0; i < raw.length() && sb.length() < LOG_FIELD_MAX_LENGTH; i++) {
            char c = raw.charAt(i);
            if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c < 0x20 || c == 0x7F) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        if (raw.length() > LOG_FIELD_MAX_LENGTH) {
            sb.append("...[truncated]");
        }
        return sb.toString();
    }

    /**
     * 业务异常（携带 errorCode 的受控异常）
     */
    @ExceptionHandler(BusinessException.class)
    public Result handleBusinessException(BusinessException e) {
        log.warn("BusinessException: code={}, msg={}", e.getErrorCode(), e.getMessage());
        return Result.fail(e.getErrorCode(), e.getMessage());
    }

    /**
     * JSR-303 @RequestBody 参数校验失败
     * 使用 PARAM_INVALID 而非 PHONE_INVALID（后者语义过窄）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        log.warn("MethodArgumentNotValidException: field={}, msg={}",
                fieldError != null ? fieldError.getField() : "unknown", message);
        return Result.fail(ErrorCode.PARAM_INVALID, message);
    }

    /**
     * JSR-303 @RequestParam / @PathVariable 参数校验失败
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result handleConstraintViolationException(ConstraintViolationException e) {
        ConstraintViolation<?> violation = e.getConstraintViolations().iterator().next();
        String message = violation != null ? violation.getMessage() : "参数校验失败";
        log.warn("ConstraintViolationException: {}", message);
        return Result.fail(ErrorCode.PARAM_INVALID, message);
    }

    /**
     * 表单绑定异常
     */
    @ExceptionHandler(BindException.class)
    public Result handleBindException(BindException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数绑定失败";
        log.warn("BindException: {}", message);
        return Result.fail(ErrorCode.PARAM_INVALID, message);
    }

    /**
     * 请求体解析失败（如类型不匹配、日期格式错误、JSON 结构错误）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result handleHttpMessageNotReadableException(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("HttpMessageNotReadableException at {} - {}",
                sanitizeForLog(request.getRequestURI()), sanitizeForLog(e.getMessage()));
        return Result.fail(ErrorCode.PARAM_INVALID, "参数格式不正确，请检查后重试");
    }

    /**
     * 缺少必要请求参数（如 seckill-orders 缺 voucherId）
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        log.warn("MissingServletRequestParameterException: {}", e.getParameterName());
        return Result.fail(ErrorCode.PARAM_INVALID, "缺少必要参数: " + e.getParameterName());
    }

    /**
     * 请求路径不存在（无对应 Controller 路由 / 静态资源）
     * <p>
     * Spring 6.1 对未匹配任何 Handler 的请求（且无对应静态资源）抛出
     * {@link NoResourceFoundException}。此前被下面对 {@code Exception.class}
     * 的兜底捕获，导致返回 HTTP 200 +「系统繁忙」，语义错误。此处单独处理，
     * 返回规范的 HTTP 404 与标准 {@link Result}，并按最精确类型优先分流，
     * 不影响任何既有异常分支。
     * </p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        log.warn("Resource not found: {} {}", request.getMethod(), sanitizeForLog(request.getRequestURI()));
        return Result.fail("40400", "资源不存在");
    }

    /**
     * 商家权限拒绝（店铺归属校验失败）
     * <p>
     * 由 {@link MerchantAccessException} 携带业务错误码与 HTTP 状态码：
     * 未登录 → 401 + 10001、非商家 / 非 owner → 403 + 40003 / 40004、
     * 店铺不存在 → 200 + 40001（沿用项目「业务错误 = HTTP 200」惯例）。
     * 按最精确类型优先分流，不影响既有任何异常分支。
     * </p>
     */
    @ExceptionHandler(MerchantAccessException.class)
    public ResponseEntity<Result<Void>> handleMerchantAccessException(MerchantAccessException e) {
        log.warn("MerchantAccessException: code={}, msg={}, httpStatus={}",
                e.getErrorCode().getCode(), e.getErrorCode().getMsg(), e.getStatus().value());
        return ResponseEntity.status(e.getStatus()).body(Result.fail(e.getErrorCode()));
    }

    /**
     * 请求参数类型转换失败（如 /shops/1 OR 1=1）
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        log.warn("MethodArgumentTypeMismatchException: name={}, value={}",
                e.getName(), sanitizeForLog(String.valueOf(e.getValue())));
        return Result.fail(ErrorCode.PARAM_INVALID, "参数类型错误: " + e.getName());
    }

    /**
     * 请求 Content-Type 不受支持（如误发 {@code Content-Type: json}、
     * 或缺省被 curl 补成 {@code application/x-www-form-urlencoded}）
     * <p>
     * 此前 {@code HttpMediaTypeNotSupportedException extends ServletException}
     * （受检异常）会落到下面 {@code Exception.class} 的兜底分支，
     * 返回 HTTP 200 + 99999「系统繁忙」——客户端无法区分
     * 「服务端挂了」和「你请求头写错了」。这里单独处理，
     * 返回语义正确的 HTTP 415。
     * </p>
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Result<String>> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        log.warn("HttpMediaTypeNotSupportedException at {} - {}", sanitizeForLog(request.getRequestURI()),
                sanitizeForLog(e.getMessage()));
        // e.getContentType() 对「非法 mime 串」（如 "json"）返回 null，此时退回原始请求头，
        // 否则用户看到的提示会是「不支持的 Content-Type: null」。
        String actual = e.getContentType() != null
                ? e.getContentType().toString()
                : request.getContentType();
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(Result.fail(ErrorCode.PARAM_INVALID, "不支持的 Content-Type: " + actual));
    }

    /**
     * 请求方法不被支持（如对只读接口发写请求）
     * <p>
     * 2026-10-06 审查中实测：{@code POST /voucher-orders/{id}/status} 会返回
     * HTTP 200 + 99999「系统繁忙」，日志里是
     * {@code HttpRequestMethodNotSupportedException: Request method 'POST' is not supported}
     * —— 与 F-5 同源（协议层异常没进 handler 列表，落到 {@code Exception} 兜底）。
     * 这里返回语义正确的 HTTP 405。
     * </p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<String>> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("HttpRequestMethodNotSupportedException at {} - method={}", sanitizeForLog(request.getRequestURI()),
                sanitizeForLog(request.getMethod()));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(ErrorCode.PARAM_INVALID, "不支持的请求方法: " + request.getMethod()));
    }

    /**
     * multipart 请求缺少文件 part（如 {@code POST /upload/icon -F "x=1"} 缺 file）
     * <p>
     * 同样是被 {@code Exception.class} 兜底吞成 99999，此处返回 HTTP 400。
     * </p>
     */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Result<String>> handleMissingServletRequestPart(
            MissingServletRequestPartException e, HttpServletRequest request) {
        log.warn("MissingServletRequestPartException at {} - name={}", sanitizeForLog(request.getRequestURI()),
                sanitizeForLog(e.getRequestPartName()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.PARAM_INVALID, "缺少文件参数: " + e.getRequestPartName()));
    }

    /**
     * 请求体不是 multipart 却被当作 multipart 解析
     * <p>
     * {@code MultipartException extends NestedRuntimeException}，
     * 此前落到 {@code RuntimeException.class} 分支 → 99999 + ERROR 级刷栈；
     * 实际上这是客户端请求头/体格式错误，应返回 HTTP 400 且不算服务端错误。
     * </p>
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Result<String>> handleMultipartException(
            MultipartException e, HttpServletRequest request) {
        log.warn("MultipartException at {} - {}", sanitizeForLog(request.getRequestURI()),
                sanitizeForLog(e.getMessage()));
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.PARAM_INVALID, "请求不是 multipart 格式或文件超过大小限制"));
    }

    /**
     * 运行时异常（非业务异常的 RuntimeException）
     */
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e, HttpServletRequest request) {
        log.error("RuntimeException at {} - {}", sanitizeForLog(request.getRequestURI()),
                sanitizeForLog(e.getMessage()), e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    /**
     * 兜底：所有其他未处理异常
     */
    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e, HttpServletRequest request) {
        log.error("Unexpected exception at {} - {}", sanitizeForLog(request.getRequestURI()),
                sanitizeForLog(e.getMessage()), e);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    // ----------------------------------------------------------------
    // 内嵌业务异常类（供 Service 层 throw 使用）
    // ----------------------------------------------------------------

    /**
     * 业务异常，携带 errorCode（String），由 WebExceptionAdvice 统一处理
     * 使用方式：throw new BusinessException(ErrorCode.VOUCHER_NOT_FOUND)
     */
    public static class BusinessException extends RuntimeException {
        private final String errorCode;

        public BusinessException(ErrorCode errorCode) {
            super(errorCode.getMsg());
            this.errorCode = errorCode.getCode();
        }

        public BusinessException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
