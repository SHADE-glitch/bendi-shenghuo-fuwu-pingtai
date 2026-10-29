package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import com.hmdp.utils.RedisConstants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 新用户首次登录（自动注册）集成测试
 * <p>
 * 覆盖真实登录注册链路：不存在手机号 -> 验证码校验 -> 创建用户 -> 返回 token
 * -> user.role=0 -> Redis 登录态含 role。回归 Phase 2.1 引入的
 * 「新用户首次登录 NPE -> 99999」缺陷（{@code createUserWithPhone} 未初始化 role）。
 * </p>
 */
@DisplayName("新用户首次登录注册（role 链路）")
class NewUserLoginRegisterTest extends WebIntegrationTestBase {

    private String phone;
    private String token;

    @BeforeEach
    void setUp() {
        // 生成 11 位、合法前缀(188)且本次唯一的手机号，确保走「自动注册」分支
        phone = "188" + String.format("%08d", System.nanoTime() % 100000000L);

        // 自愈：清理上一轮异常中断可能残留的同 phone 用户
        jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id IN (SELECT id FROM tb_user WHERE phone = ?)", phone);
        jdbcTemplate.update("DELETE FROM tb_user WHERE phone = ?", phone);

        // 写入验证码（与应用登录校验读取的 key 一致：login:code:{phone}）
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, "123456", Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() {
        logout(token);
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);
        jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id IN (SELECT id FROM tb_user WHERE phone = ?)", phone);
        jdbcTemplate.update("DELETE FROM tb_user WHERE phone = ?", phone);
    }

    @Test
    @DisplayName("首次登录自动注册成功，返回 role=0 且 Redis 登录态含 role=0")
    void newUserFirstLoginRegistersWithRole() throws Exception {
        String body = mockMvc.perform(post("/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.role").value(0))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        token = JsonPath.read(body, "$.data.token");
        assertThat(token).isNotBlank();

        // Redis 登录态 Hash 必须存在 role 字段，且为 0
        Object redisRole = stringRedisTemplate.opsForHash()
                .get(RedisConstants.LOGIN_USER_KEY + token, "role");
        assertThat(redisRole).isEqualTo("0");

        // 数据库新用户 role 应为 0（DEFAULT 生效）
        Integer dbRole = jdbcTemplate.queryForObject(
                "SELECT role FROM tb_user WHERE phone = ?", Integer.class, phone);
        assertThat(dbRole).isEqualTo(0);
    }
}