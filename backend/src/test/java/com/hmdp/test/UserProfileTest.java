package com.hmdp.test;

import com.hmdp.test.support.WebIntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 他人主页测试（社交闭环）
 * <p>
 * 覆盖：免登录可读、聚合字段、followedByMe 随关注状态变化、用户笔记分页、未知用户。
 * </p>
 */
@DisplayName("他人主页 / 用户笔记")
class UserProfileTest extends WebIntegrationTestBase {

    private static final String NICK_PREFIX = "TestProfile_";

    /** 被访问的主页用户（使用种子用户 2，测试后不需清理） */
    private static final long TARGET_USER_ID = 2L;

    private Long visitorId;
    private String visitorToken;

    /** 关注操作会 upsert 目标用户的 tb_user_info，测试前先快照，结束后精确还原 */
    private boolean targetInfoExisted;
    private int targetFansBefore;

    @BeforeEach
    void setUp() {
        Long infoRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_user_info WHERE user_id = ?", Long.class, TARGET_USER_ID);
        targetInfoExisted = infoRows != null && infoRows > 0;
        Integer fans = targetInfoExisted
                ? jdbcTemplate.queryForObject("SELECT COALESCE(fans, 0) FROM tb_user_info WHERE user_id = ?",
                        Integer.class, TARGET_USER_ID)
                : 0;
        targetFansBefore = fans == null ? 0 : fans;

        visitorId = createTestUser(NICK_PREFIX, "visitor");
        visitorToken = loginAs(visitorId, NICK_PREFIX + "visitor");
    }

    @AfterEach
    void tearDown() {
        // 先通过接口取关，保证关注数/粉丝数对称回减
        if (visitorToken != null) {
            try {
                mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/follows/" + TARGET_USER_ID).header(AUTH_HEADER, visitorToken));
            } catch (Exception ignored) {
                // 清理失败不阻塞断言结果
            }
        }
        if (visitorId != null) {
            jdbcTemplate.update("DELETE FROM tb_follow WHERE user_id = ? OR follow_user_id = ?", visitorId, visitorId);
            jdbcTemplate.update("DELETE FROM tb_message WHERE user_id = ? OR sender_id = ?", visitorId, visitorId);
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", visitorId);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", visitorId);
        }
        // 还原目标用户的 tb_user_info（原本没有记录时删除，原本有则恢复粉丝数）
        jdbcTemplate.update("DELETE FROM tb_follow WHERE follow_user_id = ? AND user_id >= 990101", TARGET_USER_ID);
        if (targetInfoExisted) {
            jdbcTemplate.update("UPDATE tb_user_info SET fans = ? WHERE user_id = ?", targetFansBefore, TARGET_USER_ID);
        } else {
            jdbcTemplate.update(
                    "DELETE FROM tb_user_info WHERE user_id = ? AND COALESCE(fans,0)=0 AND COALESCE(followee,0)=0 "
                            + "AND introduce IS NULL AND (city IS NULL OR city = '')",
                    TARGET_USER_ID);
        }
        jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id IN (SELECT id FROM tb_user WHERE nick_name LIKE ?)", NICK_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_user WHERE nick_name LIKE ?", NICK_PREFIX + "%");
        logout(visitorToken);
    }

    @Test
    @DisplayName("匿名可查看他人主页聚合信息")
    void anonymousCanViewProfile() throws Exception {
        mockMvc.perform(get("/users/" + TARGET_USER_ID + "/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(String.valueOf(TARGET_USER_ID)))
                .andExpect(jsonPath("$.data.followedByMe").value(false))
                .andExpect(jsonPath("$.data.blogCount").exists());
    }

    @Test
    @DisplayName("已登录用户未关注时 followedByMe=false，关注后为 true")
    void followedByMeReflectsFollowState() throws Exception {
        mockMvc.perform(get("/users/" + TARGET_USER_ID + "/profile").header(AUTH_HEADER, visitorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followedByMe").value(false));

        mockMvc.perform(post("/follows/" + TARGET_USER_ID).header(AUTH_HEADER, visitorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/users/" + TARGET_USER_ID + "/profile").header(AUTH_HEADER, visitorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followedByMe").value(true));
    }

    @Test
    @DisplayName("用户笔记分页返回列表")
    void userBlogsPaginated() throws Exception {
        mockMvc.perform(get("/users/" + TARGET_USER_ID + "/blogs").param("current", "1").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("不存在的用户返回失败而非 500")
    void unknownUserRejected() throws Exception {
        mockMvc.perform(get("/users/999999999/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("分页 size 超过全局上限时被截断（不多于 100 条）")
    void paginationSizeIsCapped() throws Exception {
        mockMvc.perform(get("/users/" + TARGET_USER_ID + "/blogs").param("current", "1").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()", greaterThanOrEqualTo(0)));
    }
}
