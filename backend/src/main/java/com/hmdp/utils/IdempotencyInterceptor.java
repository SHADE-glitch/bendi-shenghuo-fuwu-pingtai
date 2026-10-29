package com.hmdp.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

/**
 * 通用幂等拦截器（Header: Idempotency-Key）
 */
public class IdempotencyInterceptor implements HandlerInterceptor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String IDEMPOTENCY_PREFIX = "idem:req:";
    private static final long IDEMPOTENCY_TTL_MINUTES = 5L;

    private final StringRedisTemplate stringRedisTemplate;

    public IdempotencyInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }

        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            return true;
        }

        Long userId = UserHolder.getUser() != null ? UserHolder.getUser().getId() : null;
        String userPart = userId == null ? "anonymous" : String.valueOf(userId);
        String redisKey = IDEMPOTENCY_PREFIX + userPart + ":" + method + ":" + request.getRequestURI() + ":" + idempotencyKey.trim();

        Boolean firstRequest = stringRedisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", IDEMPOTENCY_TTL_MINUTES, TimeUnit.MINUTES);
        if (Boolean.TRUE.equals(firstRequest)) {
            return true;
        }

        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        Result<Void> body = Result.fail(ErrorCode.IDEMPOTENCY_REPLAY);
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(body));
        return false;
    }
}
