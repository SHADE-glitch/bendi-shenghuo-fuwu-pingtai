package com.hmdp.config;

import com.hmdp.utils.IdempotencyInterceptor;
import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import com.hmdp.utils.TraceInterceptor;
import com.hmdp.utils.WriteAuthInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置类
 * <p>
 * 拦截器执行顺序（从低到高）：
 * 1. TraceInterceptor (-2): 生成 traceId，用于日志追踪
 * 2. RefreshTokenInterceptor (-1): 解析 Token，加载用户信息到 ThreadLocal，刷新 TTL
 * 3. LoginInterceptor (0): 校验 ThreadLocal 是否有用户，无则返回 401
 * 4. IdempotencyInterceptor (1): 检查幂等性令牌（仅当请求携带 Idempotency-Key 时生效）
 * </p>
 * 
 * <strong>认证流程说明：</strong>
 * - 支持两种 Header 格式：<code>Authorization</code>（标准）或 <code>authorization</code>（兼容旧版）
 * - Token 存储在 Redis Key: <code>login:token:{token}</code>，TTL 36000 秒（10 小时）
 * - 每次请求自动刷新 TTL，实现"滑动窗口"式登录保持
 * </p>
 */
@Configuration
public class MvcConfig implements WebMvcConfigurer {

    // new出来的对象是无法直接注入IOC容器的（LoginInterceptor是直接new出来的）
    // 所以这里需要再配置类中注入，然后通过构造器传入到当前类中
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry
            .addResourceHandler("/upload/**")
            .addResourceLocations("file:uploads/");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // traceId (MDC) should be available for all requests as early as possible
        registry
            .addInterceptor(new TraceInterceptor())
            .addPathPatterns("/**")
            .order(-2);

        // 添加刷新token的拦截器
        registry
            .addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
            .addPathPatterns("/**")
            .order(-1);

        // 添加登录拦截器
        registry
            .addInterceptor(new LoginInterceptor())
            // 设置放行请求
            .excludePathPatterns(
                "/users/code",
                "/users/code/mock",
                "/users/login",
                "/users/*/profile",
                "/users/*/blogs",
                "/shops/**",
                "/shop-types/**",
                "/vouchers/**",
                "/health",
                "/ping"
            )
            .order(0); // 优先级默认都是0，值越大优先级越低

        // 写操作登录校验：/shops/**、/vouchers/** 读取匿名放行，但新增/修改必须登录
        registry
            .addInterceptor(new WriteAuthInterceptor())
            .addPathPatterns("/shops/**", "/vouchers/**")
            .order(0);

        // 通用幂等拦截：仅在请求携带 Idempotency-Key 时生效
        registry
            .addInterceptor(new IdempotencyInterceptor(stringRedisTemplate))
            .addPathPatterns("/**")
            .order(1);
    }
}
