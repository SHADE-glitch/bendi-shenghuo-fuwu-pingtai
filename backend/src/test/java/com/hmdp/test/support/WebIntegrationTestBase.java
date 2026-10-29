package com.hmdp.test.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MockMvc 集成测试基类
 * <p>
 * 提供「伪造登录态」能力：直接向 Redis 写入与应用登录后结构一致的登录缓存
 * （Hash: id / nickName / icon），使 {@code RefreshTokenInterceptor}
 * 能在后续请求中还原出登录用户，从而覆盖完整拦截器链。
 * </p>
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class WebIntegrationTestBase {

    /** 请求头名称（RefreshTokenInterceptor 同时兼容 Authorization / authorization） */
    protected static final String AUTH_HEADER = "authorization";

    @Resource
    protected MockMvc mockMvc;

    @Resource
    protected StringRedisTemplate stringRedisTemplate;

    @Resource
    protected JdbcTemplate jdbcTemplate;

    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 伪造登录态（普通用户 role=0），返回可用于请求头的 token
     */
    protected String loginAs(Long userId, String nickName) {
        return loginAs(userId, nickName, 0);
    }

    /**
     * 伪造登录态（商家 role=1），返回可用于请求头的 token
     * <p>
     * 供 Phase 2.2 商家权限测试使用：与 {@link #loginAs(Long, String)} 唯一区别是
     * Redis 登录 Hash 中 {@code role=1}，其余结构完全一致。
     * </p>
     */
    protected String loginAsMerchant(Long userId, String nickName) {
        return loginAs(userId, nickName, 1);
    }

    /**
     * 伪造登录态（指定角色），返回可用于请求头的 token
     *
     * @param role 角色：0=普通用户 1=商家（写入 Redis 登录 Hash，供 RefreshTokenInterceptor 反填）
     */
    protected String loginAs(Long userId, String nickName, int role) {
        String token = "it" + userId + "-" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> map = new HashMap<>();
        map.put("id", String.valueOf(userId));
        map.put("nickName", nickName == null ? "集成测试用户" : nickName);
        map.put("icon", "");
        map.put("role", String.valueOf(role)); // 默认普通用户，保证 Phase 2 权限测试的登录态稳定
        String key = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(key, map);
        stringRedisTemplate.expire(key, Duration.ofMinutes(30));
        return token;
    }

    protected void logout(String token) {
        if (token != null) {
            stringRedisTemplate.delete(RedisConstants.LOGIN_USER_KEY + token);
        }
    }

    protected String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    /** 测试用户序号：保证 phone 唯一且长度可控（tb_user.phone varchar(11)） */
    private static final java.util.concurrent.atomic.AtomicInteger USER_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * 创建测试用户，返回自增主键
     * <p>
     * tb_user.nick_name 为 varchar(32)，此处按 22 字符截断前缀再拼接序号，
     * 避免超长导致 Data truncation。
     * </p>
     */
    protected Long createTestUser(String nickPrefix, String suffix) {
        int seq = USER_SEQ.incrementAndGet();
        String phone = String.format("199%08d", seq % 100000000);
        String base = nickPrefix + suffix;
        String nick = (base.length() > 22 ? base.substring(0, 22) : base) + "_" + seq;

        // 自愈：清理上一轮异常中断可能残留的同 phone 用户
        jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id IN (SELECT id FROM tb_user WHERE phone = ?)", phone);
        jdbcTemplate.update("DELETE FROM tb_user WHERE phone = ?", phone);
        jdbcTemplate.update("INSERT INTO tb_user (phone, nick_name) VALUES (?, ?)", phone, nick);

        Long id = jdbcTemplate.queryForObject("SELECT id FROM tb_user WHERE phone = ?", Long.class, phone);
        if (id == null) {
            throw new IllegalStateException("测试用户创建失败");
        }
        return id;
    }
}
