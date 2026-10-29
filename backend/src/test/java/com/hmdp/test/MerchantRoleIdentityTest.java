package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import com.hmdp.utils.RedisConstants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Merchant 测试身份链验证（Phase 2.2.1）
 * <p>
 * 目的：在接入任何权限逻辑<b>之前</b>，先证明测试基建伪造的「商家身份」本身是有效的，
 * 即 {@code loginAsMerchant} 写入的 {@code role=1} 能完整流经：
 * </p>
 * <pre>
 *   loginAsMerchant(token)
 *        │  写入 Redis Hash  login:token:{token}  {id, nickName, icon, role=1}
 *        ▼
 *   RefreshTokenInterceptor(order=-1)  ── BeanUtil.fillBeanWithMap ──►  UserDTO(role=1)
 *        │  UserHolder.saveUser(userDTO)
 *        ▼
 *   GET /users/me  ──►  Result.data = UserHolder.getUser()  ──►  data.role == 1
 * </pre>
 * <p>
 * <b>为什么用 /users/me 而不是新增 debug Controller：</b>
 * {@code UserController.getCurrentUser()} 直接把 {@code UserHolder.getUser()} 原样返回，
 * 是「ThreadLocal 中真实用户态」的唯一只读出口；且该路径未被 LoginInterceptor 放行，
 * 天然要求登录态。因此它能同时证明「拦截器填充成功」与「role 字段可被观察到」，
 * 无需为测试改动任何生产代码。
 * </p>
 * <p>
 * 本测试<b>只读</b>：不写数据库，仅在 Redis 中伪造登录态，结束后清理 token。
 * </p>
 */
@DisplayName("Merchant 测试身份链（loginAsMerchant -> Redis -> RefreshTokenInterceptor -> UserHolder）")
class MerchantRoleIdentityTest extends WebIntegrationTestBase {

    /** 复用种子用户 id，仅作为伪造登录态的主体标识，不产生数据库写入 */
    private static final long USER_ID = 1L;

    private String merchantToken;
    private String plainToken;

    @AfterEach
    void tearDown() {
        logout(merchantToken);
        logout(plainToken);
        merchantToken = null;
        plainToken = null;
    }

    @Test
    @DisplayName("loginAsMerchant 的 role=1 贯通到 UserHolder（Redis Hash -> /users/me）")
    void merchantRoleFlowsThroughWholeChain() throws Exception {
        merchantToken = loginAsMerchant(USER_ID, "身份链商家");

        // 证据 1：Redis 登录态 Hash 中 role 为 "1"（伪造身份的源头）
        Object redisRole = stringRedisTemplate.opsForHash()
                .get(RedisConstants.LOGIN_USER_KEY + merchantToken, "role");
        assertThat(redisRole).isEqualTo("1");

        // 证据 2：/users/me 回显的 UserHolder 中 role=1（拦截器已成功反填 UserDTO）
        mockMvc.perform(get("/users/me").header(AUTH_HEADER, merchantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(String.valueOf(USER_ID))) // Long -> String（JacksonConfig）
                .andExpect(jsonPath("$.data.role").value(1));
    }

    @Test
    @DisplayName("loginAs（默认）的 role=0 同样贯通到 UserHolder")
    void plainUserRoleFlowsThroughWholeChain() throws Exception {
        plainToken = loginAs(USER_ID, "身份链普通用户");

        Object redisRole = stringRedisTemplate.opsForHash()
                .get(RedisConstants.LOGIN_USER_KEY + plainToken, "role");
        assertThat(redisRole).isEqualTo("0");

        mockMvc.perform(get("/users/me").header(AUTH_HEADER, plainToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(String.valueOf(USER_ID))) // Long -> String（JacksonConfig）
                .andExpect(jsonPath("$.data.role").value(0));
    }

    @Test
    @DisplayName("商家与普通用户登录态仅 role 不同（隔离出 Phase 2.2 唯一变量）")
    void merchantAndPlainDifferOnlyByRole() throws Exception {
        merchantToken = loginAsMerchant(USER_ID, "同一昵称");
        plainToken = loginAs(USER_ID, "同一昵称");

        String merchantBody = mockMvc.perform(get("/users/me").header(AUTH_HEADER, merchantToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String plainBody = mockMvc.perform(get("/users/me").header(AUTH_HEADER, plainToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 不比较完整报文（traceId 每次请求都不同），只比较身份字段：
        // id / nickName / icon 一致，role 是唯一差异 —— 保证后续权限测试的差异只可能来自 role
        assertThat(JsonPath.<Object>read(merchantBody, "$.data.id"))
                .isEqualTo(JsonPath.<Object>read(plainBody, "$.data.id"));
        assertThat(JsonPath.<Object>read(merchantBody, "$.data.nickName"))
                .isEqualTo(JsonPath.<Object>read(plainBody, "$.data.nickName"));
        assertThat(JsonPath.<Object>read(merchantBody, "$.data.icon"))
                .isEqualTo(JsonPath.<Object>read(plainBody, "$.data.icon"));
        assertThat(JsonPath.<Integer>read(merchantBody, "$.data.role")).isEqualTo(1);
        assertThat(JsonPath.<Integer>read(plainBody, "$.data.role")).isEqualTo(0);
    }

    @Test
    @DisplayName("无 token 访问 /users/me 被 LoginInterceptor 短路为 401 + 10001")
    void anonymousHasNoIdentity() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10001"))
                .andExpect(jsonPath("$.errorMsg").value("用户未登录"));
    }

    @Test
    @DisplayName("伪造登录态会被 RefreshTokenInterceptor 续期（TTL 保持 > 0）")
    void tokenTtlIsRefreshed() throws Exception {
        merchantToken = loginAsMerchant(USER_ID, "续期商家");

        // 先人为把 TTL 压到 1 分钟，模拟临近过期
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY + merchantToken,
                java.time.Duration.ofMinutes(1));

        mockMvc.perform(get("/users/me").header(AUTH_HEADER, merchantToken))
                .andExpect(status().isOk());

        Long ttl = stringRedisTemplate.getExpire(RedisConstants.LOGIN_USER_KEY + merchantToken,
                java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isGreaterThan(60L);
    }
}
