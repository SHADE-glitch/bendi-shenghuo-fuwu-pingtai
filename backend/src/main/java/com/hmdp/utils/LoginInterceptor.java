package com.hmdp.utils;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 登录拦截器
 * <p>
 * 实现 {@link HandlerInterceptor}，在请求进入 Controller 前检查 ThreadLocal 中
 * 是否存在当前登录用户。未登录时返回 HTTP 401 状态码及统一的 JSON 错误响应。
 * </p>
 * <p>
 * 该拦截器需配合 {@link RefreshTokenInterceptor} 使用，后者负责从 Redis
 * 解析 Token 并填充用户信息到 ThreadLocal，本拦截器仅做最终的登录态校验。
 * </p>
 */
public class LoginInterceptor implements HandlerInterceptor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 请求预处理：校验用户是否已登录。
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler  被拦截的处理器
     * @return true 表示已登录，放行；false 表示未登录，已写入 401 错误响应
     * @throws Exception 序列化响应体时可能抛出
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 上传的静态资源（GET /upload/**）无需登录即可访问
        if ("GET".equals(request.getMethod()) && request.getRequestURI().startsWith("/upload/")) {
            return true;
        }
        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            // 未登录：返回 401 + JSON Result（携带 errorCode/traceId）
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");

            Result<Void> body = Result.fail(ErrorCode.AUTH_REQUIRED);
            response.getWriter().write(OBJECT_MAPPER.writeValueAsString(body));
            return false;
        }
        // 有用户，则放行
        return true;
    }
}

