package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 浏览历史测试：去重写入、目标类型校验、笔记类型、清空
 */
@DisplayName("浏览历史（去重 / 类型校验 / 清空）")
class BrowseHistoryTest extends WebIntegrationTestBase {

    private static final String NICK_PREFIX = "TestHistory_";
    /** 目标类型：1-店铺 2-笔记 */
    private static final int TYPE_SHOP = 1;
    private static final int TYPE_BLOG = 2;

    private Long userId;
    private String token;

    @BeforeEach
    void setUp() {
        userId = createTestUser(NICK_PREFIX, "user");
        token = loginAs(userId, NICK_PREFIX + "user");
    }

    @AfterEach
    void tearDown() {
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM tb_browse_history WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("同一目标重复上报只保留一行（唯一索引去重）")
    void duplicateTargetDeduped() throws Exception {
        record(TYPE_SHOP, 1L);
        record(TYPE_SHOP, 1L);
        record(TYPE_SHOP, 1L);

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_browse_history WHERE user_id = ? AND target_type = 1 AND target_id = 1",
                Long.class, userId);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("笔记类型（targetType=2）可正常写入")
    void blogTypeAccepted() throws Exception {
        record(TYPE_BLOG, 4L);

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_browse_history WHERE user_id = ? AND target_type = 2 AND target_id = 4",
                Long.class, userId);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("非法 targetType 返回 10008")
    void invalidTargetTypeRejected() throws Exception {
        mockMvc.perform(post("/browse-history")
                        .header(AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":3,\"targetId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10008"));
    }

    @Test
    @DisplayName("目标不存在时被拒绝")
    void unknownTargetRejected() throws Exception {
        mockMvc.perform(post("/browse-history")
                        .header(AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":1,\"targetId\":999999999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40001"));
    }

    @Test
    @DisplayName("我的历史列表与清空")
    void listAndClear() throws Exception {
        record(TYPE_SHOP, 1L);

        mockMvc.perform(get("/browse-history/mine").header(AUTH_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(delete("/browse-history").header(AUTH_HEADER, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_browse_history WHERE user_id = ?", Long.class, userId);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("匿名上报返回 401")
    void anonymousRejected() throws Exception {
        mockMvc.perform(post("/browse-history")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":1,\"targetId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------

    private void record(int targetType, long targetId) throws Exception {
        mockMvc.perform(post("/browse-history")
                        .header(AUTH_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":" + targetType + ",\"targetId\":" + targetId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
