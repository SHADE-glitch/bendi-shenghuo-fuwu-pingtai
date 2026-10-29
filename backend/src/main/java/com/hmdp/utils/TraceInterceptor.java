package com.hmdp.utils;

import cn.hutool.core.util.IdUtil;
import org.slf4j.MDC;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Trace ID 拦截器
 * <p>
 * 实现 {@link HandlerInterceptor}，为每个请求生成唯一的 traceId 并放入
 * SLF4J 的 {@link MDC}（Mapped Diagnostic Context），使日志输出中能够
 * 携带请求级别的追踪标识，便于在分布式环境中串联同一次请求的全部日志。
 * </p>
 * <p>
 * 请求完成后自动从 MDC 中移除 traceId，防止线程池复用导致日志串扰。
 * </p>
 */
public class TraceInterceptor implements HandlerInterceptor {

    /**
     * 请求预处理：生成 traceId 并写入 MDC。
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler  被拦截的处理器
     * @return 始终返回 true 放行
     * @throws Exception 生成 UUID 或操作 MDC 时可能抛出
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 生成 traceId
        String traceId = IdUtil.fastSimpleUUID();
        // 放入 MDC
        MDC.put("traceId", traceId);
        return true;
    }

    /**
     * 请求完成后清理：从 MDC 中移除 traceId。
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler  被拦截的处理器
     * @param ex       处理过程中抛出的异常，无异常时为 null
     * @throws Exception 操作 MDC 时可能抛出
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 清理 MDC
        MDC.remove("traceId");
    }
}
