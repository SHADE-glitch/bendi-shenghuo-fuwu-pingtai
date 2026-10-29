package com.hmdp.service.impl;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.LoginResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.utils.RedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录验证码发送频率 / 校验尝试限制单测（P1-1）
 *
 * <p>
 * <b>性质：纯单元测试，零基础设施。</b>不启动 Spring、不连 Redis/MySQL——
 * {@link StringRedisTemplate} 与 {@link UserMapper} 均为 Mockito 桩，
 * {@link UserServiceImpl} 直接 new 出来后经反射注入。覆盖三条新闸门：
 * </p>
 * <ul>
 *   <li>发送侧：分钟级冷却（{@code login:code:limit:{phone}} SETNX）</li>
 *   <li>发送侧：小时级配额（{@code login:code:count:{phone}} INCR，5 次/小时）</li>
 *   <li>校验侧：失败计数（{@code login:code:attempt:{phone}}），满 5 次作废验证码</li>
 * </ul>
 *
 * <p>
 * 与真实 Redis 的交互语义（SETNX / INCR / EXPIRE / DEL）由集成测试
 * {@code NewUserLoginRegisterTest} 等覆盖；本类只钉住「限流判定分支」。
 * </p>
 */
@DisplayName("登录验证码频率与尝试限制（P1-1）")
class UserServiceImplLoginCodeTest {

    private static final String PHONE = "13800138000";
    private static final String CODE_KEY = RedisConstants.LOGIN_CODE_KEY + PHONE;
    private static final String LIMIT_KEY = RedisConstants.LOGIN_CODE_SEND_LIMIT_KEY + PHONE;
    private static final String COUNT_KEY = RedisConstants.LOGIN_CODE_SEND_COUNT_KEY + PHONE;
    private static final String ATTEMPT_KEY = RedisConstants.LOGIN_CODE_ATTEMPT_KEY + PHONE;

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private HashOperations<String, Object, Object> hashOperations;
    private UserMapper userMapper;
    private UserServiceImpl userService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        hashOperations = mock(HashOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.opsForHash()).thenReturn(hashOperations);
        userMapper = mock(UserMapper.class);

        userService = new UserServiceImpl();
        inject(userService, "stringRedisTemplate", stringRedisTemplate);
        inject(userService, "baseMapper", userMapper);
    }

    // ---------------------------------------------------------------
    // 发送频率限制
    // ---------------------------------------------------------------

    @Test
    @DisplayName("发送：冷却期内重复发送被拒（10006），不生成、不写入验证码")
    void sendCodeRejectedWithinCooldown() {
        when(valueOperations.setIfAbsent(eq(LIMIT_KEY), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.FALSE);

        Result result = userService.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("10006");
        // 超限即拒绝：不发放验证码、不计入小时配额
        verify(valueOperations, never()).set(eq(CODE_KEY), anyString(), anyLong(), any());
        verify(valueOperations, never()).increment(COUNT_KEY);
    }

    @Test
    @DisplayName("发送：每小时配额耗尽被拒（10006），不生成、不写入验证码")
    void sendCodeRejectedWhenHourlyQuotaExhausted() {
        when(valueOperations.setIfAbsent(eq(LIMIT_KEY), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.increment(COUNT_KEY))
                .thenReturn(RedisConstants.LOGIN_CODE_SEND_MAX_PER_HOUR + 1);

        Result result = userService.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("10006");
        verify(valueOperations, never()).set(eq(CODE_KEY), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("发送：首次发放写冷却+计数+验证码，并重置失败尝试计数")
    void sendCodeSucceedsAndResetsAttemptCounter() {
        when(valueOperations.setIfAbsent(eq(LIMIT_KEY), eq("1"), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.increment(COUNT_KEY)).thenReturn(1L);

        Result result = userService.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isTrue();
        // 冷却键：SETNX + 60s TTL
        verify(valueOperations).setIfAbsent(eq(LIMIT_KEY), eq("1"),
                eq(RedisConstants.LOGIN_CODE_SEND_LIMIT_TTL), eq(TimeUnit.SECONDS));
        // 小时计数：首次 INCR 后补 TTL（否则计数键永不过期）
        verify(valueOperations).increment(COUNT_KEY);
        verify(stringRedisTemplate).expire(eq(COUNT_KEY),
                eq(RedisConstants.LOGIN_CODE_SEND_COUNT_TTL), eq(TimeUnit.SECONDS));
        // 验证码：6 位数字 + 2 分钟 TTL
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(eq(CODE_KEY), codeCaptor.capture(),
                eq(RedisConstants.LOGIN_CODE_TTL), eq(TimeUnit.MINUTES));
        assertThat(codeCaptor.getValue()).hasSize(6).containsOnlyDigits();
        // 新码发放即重置上一个码留下的失败计数
        verify(stringRedisTemplate).delete(ATTEMPT_KEY);
    }

    // ---------------------------------------------------------------
    // 校验尝试限制
    // ---------------------------------------------------------------

    @Test
    @DisplayName("登录：失败未达上限返回 10003，验证码保留（合法用户可改后重试）")
    void loginFailureBelowLimitKeepsCode() {
        when(valueOperations.get(CODE_KEY)).thenReturn("123456");
        when(valueOperations.increment(ATTEMPT_KEY)).thenReturn(1L);

        Result result = userService.login(loginForm("000000"), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("10003");
        // 首次失败给计数键设 TTL，生命周期与验证码对齐
        verify(stringRedisTemplate).expire(eq(ATTEMPT_KEY),
                eq(RedisConstants.LOGIN_CODE_TTL), eq(TimeUnit.MINUTES));
        // 未达上限：验证码不删除、不清计数
        verify(stringRedisTemplate, never()).delete(CODE_KEY);
        verify(stringRedisTemplate, never()).delete(ATTEMPT_KEY);
    }

    @Test
    @DisplayName("登录：第 5 次失败返回 10010，验证码作废并要求重新发送")
    void loginFifthFailureInvalidatesCode() {
        when(valueOperations.get(CODE_KEY)).thenReturn("123456");
        when(valueOperations.increment(ATTEMPT_KEY))
                .thenReturn(RedisConstants.LOGIN_CODE_MAX_ATTEMPTS);

        Result result = userService.login(loginForm("000000"), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("10010");
        // 达到上限：作废验证码 + 清除计数，重新发送走 sendCode 的频率闸门
        verify(stringRedisTemplate).delete(CODE_KEY);
        verify(stringRedisTemplate).delete(ATTEMPT_KEY);
        // 次数已尽，绝不能再放行登录
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("登录：验证码过期（缓存为空）同样消耗尝试次数")
    void loginWithExpiredCodeStillCountsAttempt() {
        when(valueOperations.get(CODE_KEY)).thenReturn(null);
        when(valueOperations.increment(ATTEMPT_KEY)).thenReturn(2L);

        Result result = userService.login(loginForm("123456"), null);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("10003");
        verify(valueOperations).increment(ATTEMPT_KEY);
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("登录：成功删除验证码与失败计数，返回 Token")
    void loginSuccessDeletesCodeAndAttempts() {
        when(valueOperations.get(CODE_KEY)).thenReturn("123456");
        when(userMapper.selectOne(any())).thenReturn(existingUser());

        Result result = userService.login(loginForm("123456"), null);

        assertThat(result.getSuccess()).isTrue();
        assertThat(((LoginResultDTO) result.getData()).getToken()).isNotBlank();
        // 一次性消费：成功即删码；失败计数同步清除
        verify(stringRedisTemplate).delete(CODE_KEY);
        verify(stringRedisTemplate).delete(ATTEMPT_KEY);
        // 登录态写入 Redis Hash 并设 10 小时 TTL
        verify(hashOperations).putAll(anyString(), any());
        verify(stringRedisTemplate).expire(anyString(),
                eq(RedisConstants.LOGIN_USER_TTL), eq(TimeUnit.SECONDS));
        verify(userMapper, times(1)).selectOne(any());
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    private LoginFormDTO loginForm(String code) {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(PHONE);
        form.setCode(code);
        return form;
    }

    private User existingUser() {
        User user = new User();
        user.setId(88001L);
        user.setPhone(PHONE);
        user.setNickName("单测用户");
        user.setRole(0); // 必须非空：beanToMap 的 fieldValueEditor 会对 null 调 toString() NPE
        user.setCreateTime(LocalDateTime.now());
        return user;
    }

    /**
     * 反射注入字段：先找声明类本身，找不到再沿父类向上（baseMapper 声明在
     * MyBatis-Plus 的 ServiceImpl 上，UserServiceImpl 自身看不到）。
     */
    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found on " + target.getClass());
    }
}
