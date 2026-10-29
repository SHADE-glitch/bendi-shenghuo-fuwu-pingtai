package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 消息「全部已读」测试
 * <p>只影响当前登录用户自己的未读消息，不影响他人。</p>
 */
@DisplayName("消息全部已读")
class MessageReadAllTest extends WebIntegrationTestBase {

    private static final String NICK_PREFIX = "TestMsg_";

    private Long userA;
    private Long userB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        userA = createTestUser(NICK_PREFIX, "a");
        userB = createTestUser(NICK_PREFIX, "b");
        tokenA = loginAs(userA, NICK_PREFIX + "a");
        tokenB = loginAs(userB, NICK_PREFIX + "b");
    }

    @AfterEach
    void tearDown() {
        if (userA != null && userB != null) {
            jdbcTemplate.update("DELETE FROM tb_message WHERE user_id IN (?, ?)", userA, userB);
        }
        logout(tokenA);
        logout(tokenB);
        for (Long uid : new Long[]{userA, userB}) {
            if (uid == null) continue;
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", uid);
        }
    }

    @Test
    @DisplayName("全部已读只标记本人未读消息，返回本次条数，他人消息不受影响")
    void markAllReadOnlyAffectsSelf() throws Exception {
        insertMessage(userA, 0);
        insertMessage(userA, 0);
        insertMessage(userA, 1);
        insertMessage(userB, 0);

        mockMvc.perform(post("/messages/read-all").header(AUTH_HEADER, tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(2));

        Long unreadA = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_message WHERE user_id = ? AND is_read = 0", Long.class, userA);
        Long unreadB = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_message WHERE user_id = ? AND is_read = 0", Long.class, userB);
        assertThat(unreadA).isZero();
        assertThat(unreadB).isEqualTo(1L);
    }

    @Test
    @DisplayName("无未读消息时返回 0，重复调用幂等")
    void idempotentWhenNoUnread() throws Exception {
        mockMvc.perform(post("/messages/read-all").header(AUTH_HEADER, tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
        mockMvc.perform(post("/messages/read-all").header(AUTH_HEADER, tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(0));
    }

    @Test
    @DisplayName("匿名调用返回 401")
    void anonymousRejected() throws Exception {
        mockMvc.perform(post("/messages/read-all")).andExpect(status().isUnauthorized());
    }

    private void insertMessage(Long userId, int isRead) {
        jdbcTemplate.update(
                "INSERT INTO tb_message (user_id, sender_id, content, type, related_id, is_read) VALUES (?, NULL, '集成测试消息', 1, 1, ?)",
                userId, isRead);
    }
}
