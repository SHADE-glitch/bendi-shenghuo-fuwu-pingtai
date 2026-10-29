package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 意见反馈测试：提交 / 查看 / 撤回（删除）与越权
 */
@DisplayName("意见反馈（提交 / 撤回 / 越权）")
class FeedbackCrudTest extends WebIntegrationTestBase {

    private static final String NICK_PREFIX = "TestFeedback_";

    private Long authorId;
    private Long otherId;
    private String authorToken;
    private String otherToken;

    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        authorId = createTestUser(NICK_PREFIX, "author");
        otherId = createTestUser(NICK_PREFIX, "other");
        authorToken = loginAs(authorId, NICK_PREFIX + "author");
        otherToken = loginAs(otherId, NICK_PREFIX + "other");
    }

    @AfterEach
    void tearDown() {
        for (Long id : createdIds) {
            jdbcTemplate.update("DELETE FROM tb_feedback WHERE id = ?", id);
        }
        createdIds.clear();
        logout(authorToken);
        logout(otherToken);
        for (Long uid : new Long[]{authorId, otherId}) {
            if (uid == null) continue;
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", uid);
        }
        jdbcTemplate.update("DELETE FROM tb_feedback WHERE content LIKE '集成测试反馈%'");
    }

    @Test
    @DisplayName("提交后可查询到自己的反馈")
    void submitAndQueryMine() throws Exception {
        long id = createFeedback();
        assertThat(id).isPositive();

        mockMvc.perform(get("/feedbacks/mine").header(AUTH_HEADER, authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("本人可删除自己的反馈")
    void authorCanDelete() throws Exception {
        long id = createFeedback();

        mockMvc.perform(delete("/feedbacks/" + id).header(AUTH_HEADER, authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_feedback WHERE id = ?", Long.class, id);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("他人删除按「反馈不存在或无权操作」应答，且数据未被删除")
    void nonAuthorCannotDelete() throws Exception {
        long id = createFeedback();

        mockMvc.perform(delete("/feedbacks/" + id).header(AUTH_HEADER, otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("反馈不存在或无权操作"));

        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_feedback WHERE id = ?", Long.class, id);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("匿名提交 / 删除返回 401")
    void anonymousRejected() throws Exception {
        mockMvc.perform(post("/feedbacks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"匿名反馈\",\"type\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/feedbacks/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("空内容被参数校验拦截")
    void blankContentRejected() throws Exception {
        mockMvc.perform(post("/feedbacks")
                        .header(AUTH_HEADER, authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"\",\"type\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ---------------------------------------------------------------

    private long createFeedback() throws Exception {
        String body = mockMvc.perform(post("/feedbacks")
                        .header(AUTH_HEADER, authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"集成测试反馈内容\",\"type\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Object data = JsonPath.read(body, "$.data");
        long id = Long.parseLong(String.valueOf(data));
        createdIds.add(id);
        return id;
    }
}
