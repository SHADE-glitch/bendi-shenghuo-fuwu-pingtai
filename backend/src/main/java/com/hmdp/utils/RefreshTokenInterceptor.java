package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * Token 刷新拦截器
 * <p>
 * 实现 {@link HandlerInterceptor}，负责从请求头中提取 authorization Token，
 * 从 Redis Hash 中加载用户信息并存入 {@link UserHolder}（ThreadLocal），
 * 同时刷新 Token 的有效期。请求完成后自动清理 ThreadLocal，防止内存泄漏。
 * </p>
 * <p>
 * 该拦截器应在拦截器链中排在 {@link LoginInterceptor} 之前，
 * 由它完成用户信息的填充后，LoginInterceptor 再做登录态校验。
 * </p>
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 构造拦截器实例。
     *
     * @param stringRedisTemplate Redis 操作模板，用于读取和刷新 Token
     */
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 请求预处理：从请求头提取 Token，加载用户信息到 ThreadLocal，并刷新 Token 有效期。
     * <p>
     * 若请求头中无 Token 或 Redis 中无对应用户数据，则不做任何操作直接放行，
     * 由后续的 {@link LoginInterceptor} 决定是否拦截。
     * </p>
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler  被拦截的处理器
     * @return 始终返回 true 放行
     * @throws Exception 读取 Redis 数据或转换 Bean 时可能抛出
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的 token（支持大小写两种格式）
        String token = request.getHeader("Authorization");
        if (token == null || token.isEmpty()) {
            token = request.getHeader("authorization");
        }
            
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 2.基于TOKEN获取redis中的用户
        String key  = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 3.判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }
        // 5.将查询到的hash数据转为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 6.存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser(userDTO);
        // 7.刷新token有效期
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.SECONDS);
        // 8.放行
        return true;
    }

    /**
     * 请求完成后清理：移除 ThreadLocal 中的用户信息，防止线程池复用导致的数据串扰。
     *
     * @param request  当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler  被拦截的处理器
     * @param ex       处理过程中抛出的异常，无异常时为 null
     * @throws Exception 清理 ThreadLocal 时可能抛出
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}