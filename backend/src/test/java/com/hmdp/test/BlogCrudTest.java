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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 笔记内容管理测试：编辑 / 删除与越权
 */
@DisplayName("笔记内容管理（编辑 / 删除 / 越权）")
class BlogCrudTest extends WebIntegrationTestBase {

    private static final String NICK_PREFIX = "TestBlog_";
    private static final long SHOP_ID = 1L;

    private Long authorId;
    private Long otherId;
    private String authorToken;
    private String otherToken;

    private final List<Long> createdBlogIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        authorId = createTestUser(NICK_PREFIX, "author");
        otherId = createTestUser(NICK_PREFIX, "other");
        authorToken = loginAs(authorId, NICK_PREFIX + "author");
        otherToken = loginAs(otherId, NICK_PREFIX + "other");
    }

    @AfterEach
    void tearDown() {
        for (Long id : createdBlogIds) {
            jdbcTemplate.update("DELETE FROM tb_blog_comments WHERE blog_id = ?", id);
            jdbcTemplate.update("DELETE FROM tb_blog WHERE id = ?", id);
            stringRedisTemplate.delete(RedisConstants.BLOG_LIKED_KEY + id);
        }
        createdBlogIds.clear();
        logout(authorToken);
        logout(otherToken);
        // 兜底：按作者归属清理，覆盖「笔记创建成功但断言前异常导致 id 未登记」的情况
        if (authorId != null || otherId != null) {
            jdbcTemplate.update(
                    "DELETE FROM tb_blog_comments WHERE blog_id IN (SELECT id FROM tb_blog WHERE user_id IN (?, ?))",
                    authorId, otherId);
            jdbcTemplate.update("DELETE FROM tb_blog WHERE user_id IN (?, ?)", authorId, otherId);
        }
        for (Long uid : new Long[]{authorId, otherId}) {
            if (uid == null) continue;
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id = ?", uid);
        }
        jdbcTemplate.update("DELETE FROM tb_user WHERE nick_name LIKE ?", NICK_PREFIX + "%");
    }

    @Test
    @DisplayName("作者可编辑自己的笔记，标题/内容/图片被更新")
    void authorCanUpdate() throws Exception {
        long blogId = createBlog();

        mockMvc.perform(put("/blogs/" + blogId)
                        .header(AUTH_HEADER, authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"编辑后的标题\",\"content\":\"编辑后的内容\",\"images\":[\"/imgs/a.jpg\"],\"shopId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        String title = jdbcTemplate.queryForObject("SELECT title FROM tb_blog WHERE id = ?", String.class, blogId);
        String images = jdbcTemplate.queryForObject("SELECT images FROM tb_blog WHERE id = ?", String.class, blogId);
        assertThat(title).isEqualTo("编辑后的标题");
        assertThat(images).contains("/imgs/a.jpg");
    }

    @Test
    @DisplayName("非作者编辑按「博客不存在」应答（不泄露存在性）")
    void nonAuthorCannotUpdate() throws Exception {
        long blogId = createBlog();

        mockMvc.perform(put("/blogs/" + blogId)
                        .header(AUTH_HEADER, otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"越权修改\",\"content\":\"越权\",\"images\":[],\"shopId\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("20001"));

        String title = jdbcTemplate.queryForObject("SELECT title FROM tb_blog WHERE id = ?", String.class, blogId);
        assertThat(title).isNotEqualTo("越权修改");
    }

    @Test
    @DisplayName("非作者删除被拒绝")
    void nonAuthorCannotDelete() throws Exception {
        long blogId = createBlog();

        mockMvc.perform(delete("/blogs/" + blogId).header(AUTH_HEADER, otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("20001"));

        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_blog WHERE id = ?", Long.class, blogId);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("作者删除笔记：级联清理评论与点赞集合")
    void authorDeleteCascades() throws Exception {
        long blogId = createBlog();
        jdbcTemplate.update(
                "INSERT INTO tb_blog_comments (user_id, blog_id, parent_id, answer_id, content, liked, status) VALUES (?,?,0,0,'测试评论',0,0)",
                otherId, blogId);
        stringRedisTemplate.opsForSet().add(RedisConstants.BLOG_LIKED_KEY + blogId, String.valueOf(otherId));

        mockMvc.perform(delete("/blogs/" + blogId).header(AUTH_HEADER, authorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Long blogCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_blog WHERE id = ?", Long.class, blogId);
        Long commentCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tb_blog_comments WHERE blog_id = ?", Long.class, blogId);
        assertThat(blogCount).isZero();
        assertThat(commentCount).isZero();
        assertThat(Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisConstants.BLOG_LIKED_KEY + blogId))).isFalse();
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private long createBlog() throws Exception {
        String body = mockMvc.perform(post("/blogs")
                        .header(AUTH_HEADER, authorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"集成测试笔记\",\"content\":\"集成测试内容\",\"images\":[],\"shopId\":" + SHOP_ID + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Object data = JsonPath.read(body, "$.data");
        long id = Long.parseLong(String.valueOf(data));
        createdBlogIds.add(id);
        return id;
    }
}
