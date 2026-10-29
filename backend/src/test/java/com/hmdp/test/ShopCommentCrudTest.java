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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商铺评价测试：发布 / 编辑 / 删除与越权、评分边界
 */
@DisplayName("商铺评价（编辑 / 越权 / 评分边界）")
class ShopCommentCrudTest extends WebIntegrationTestBase {

    private static final String NICK_PREFIX = "TestShopComment_";
    private static final long SHOP_ID = 1L;

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
            jdbcTemplate.update("DELETE FROM tb_shop_comments WHERE id = ?", id);
        }
        createdIds.clear();
        logout(authorToken);
        logout(otherToken);
        for (Long uid : new Long[]{authorId, otherId}) {
            if (uid == null) continue;
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", uid);
        }
        jdbcTemplate.update("DELETE FROM tb_shop_comments WHERE content LIKE '集成测试评价%'");
    }

    @Test
    @DisplayName("作者可编辑自己的评价，内容与评分被更新")
    void authorCanUpdate() throws Exception {
        long commentId = createComment();

        mockMvc.perform(put("/shop-comments/" + commentId)
                        .header(AUTH_HEADER, authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"集成测试评价-已编辑\",\"rating\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        String content = jdbcTemplate.queryForObject("SELECT content FROM tb_shop_comments WHERE id = ?", String.class, commentId);
        Integer rating = jdbcTemplate.queryForObject("SELECT rating FROM tb_shop_comments WHERE id = ?", Integer.class, commentId);
        assertThat(content).isEqualTo("集成测试评价-已编辑");
        assertThat(rating).isEqualTo(4);
    }

    @Test
    @DisplayName("非作者编辑按「评价不存在」应答")
    void nonAuthorCannotUpdate() throws Exception {
        long commentId = createComment();

        mockMvc.perform(put("/shop-comments/" + commentId)
                        .header(AUTH_HEADER, otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"越权编辑\",\"rating\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("40002"));
    }

    @Test
    @DisplayName("评分越界被参数校验拦截（10008）")
    void ratingOutOfRangeRejected() throws Exception {
        long commentId = createComment();

        mockMvc.perform(put("/shop-comments/" + commentId)
                        .header(AUTH_HEADER, authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"评分越界\",\"rating\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("10008"));
    }

    @Test
    @DisplayName("匿名写评价返回 401")
    void anonymousCreateRejected() throws Exception {
        mockMvc.perform(post("/shop-comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":1,\"content\":\"匿名评价\",\"rating\":5}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("评价不存在的店铺返回 40001")
    void unknownShopRejected() throws Exception {
        mockMvc.perform(post("/shop-comments")
                        .header(AUTH_HEADER, authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":999999999,\"content\":\"集成测试评价-未知店铺\",\"rating\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("40001"));
    }

    @Test
    @DisplayName("评价列表与汇总为公开接口")
    void listAndSummaryArePublic() throws Exception {
        mockMvc.perform(get("/shops/" + SHOP_ID + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(get("/shops/" + SHOP_ID + "/comments/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    // ---------------------------------------------------------------

    private long createComment() throws Exception {
        String body = mockMvc.perform(post("/shop-comments")
                        .header(AUTH_HEADER, authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shopId\":" + SHOP_ID + ",\"content\":\"集成测试评价\",\"rating\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Object data = JsonPath.read(body, "$.data");
        long id = Long.parseLong(String.valueOf(data));
        createdIds.add(id);
        return id;
    }
}
