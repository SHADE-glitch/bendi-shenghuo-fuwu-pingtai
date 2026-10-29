package com.hmdp.test;

import com.hmdp.controller.BlogController;
import com.hmdp.dto.BlogDetailDTO;
import com.hmdp.dto.BlogLikeDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.Resource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 笔记点赞幂等测试
 *
 * <p>验证 {@code POST/DELETE /blogs/{id}/like} 按「用户 + 笔记」幂等：
 * 同一用户重复点赞只累加一次计数、只发一条站内消息；
 * 重复取消点赞只回减一次计数，且不会减到负数。</p>
 *
 * 前置条件：
 *   - MySQL / Redis 需已启动
 *   - 测试自动创建测试用户与测试笔记，结束后清理
 *
 * 运行命令：
 *   cd backend && mvn test -Dtest=BlogLikeTest
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("笔记点赞幂等测试")
public class BlogLikeTest {

    private static final String TEST_NICK_PREFIX = "TestBlogLike_";
    /** 种子数据中必然存在的店铺 id，用于挂载测试笔记 */
    private static final Long SEED_SHOP_ID = 1L;
    /** 测试用户序号：保证 phone 唯一且长度可控 */
    private static final java.util.concurrent.atomic.AtomicInteger USER_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    @Resource
    private BlogController blogController;

    @Resource
    private IBlogService blogService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private Long authorId;
    private Long likerId;
    private Long blogId;

    @BeforeEach
    void setUp() {
        cleanTestData();
        authorId = createUser("author");
        likerId = createUser("liker");
        blogId = createBlog(authorId);
        loginAs(likerId, TEST_NICK_PREFIX + "liker");
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        cleanTestData();
    }

    @Test
    @DisplayName("重复点赞幂等：计数只 +1，且 likedByMe=true")
    void testLikeIsIdempotent() {
        Result<BlogLikeDTO> first = blogController.likeBlog(blogId);
        Result<BlogLikeDTO> second = blogController.likeBlog(blogId);

        assertThat(first.getSuccess()).isTrue();
        assertThat(second.getSuccess()).isTrue();
        assertThat(first.getData().getLiked()).isTrue();
        assertThat(second.getData().getLiked()).isTrue();
        // 两次点赞后总数应为 1（不是 2）
        assertThat(second.getData().getLikedCount()).isEqualTo(1);
        assertThat(likedCount(blogId)).isEqualTo(1);
        // 重复点赞不应重复发消息：只给作者发 1 条
        assertThat(messageCount(authorId, blogId)).isEqualTo(1);
        // 详情页 likedByMe 应为 true
        Result<BlogDetailDTO> detail = blogController.getBlogDetail(blogId);
        assertThat(detail.getData().getLikedByMe()).isTrue();
    }

    @Test
    @DisplayName("重复取消点赞幂等：计数只 -1，且不会为负")
    void testUnlikeIsIdempotent() {
        blogController.likeBlog(blogId);

        Result<BlogLikeDTO> first = blogController.unlikeBlog(blogId);
        Result<BlogLikeDTO> second = blogController.unlikeBlog(blogId);

        assertThat(first.getSuccess()).isTrue();
        assertThat(second.getSuccess()).isTrue();
        assertThat(first.getData().getLiked()).isFalse();
        assertThat(second.getData().getLiked()).isFalse();
        assertThat(likedCount(blogId)).isZero();
        // 未点赞时取消，计数不会变成负数
        assertThat(second.getData().getLikedCount()).isZero();
        Result<BlogDetailDTO> detail = blogController.getBlogDetail(blogId);
        assertThat(detail.getData().getLikedByMe()).isFalse();
    }

    @Test
    @DisplayName("点赞后再取消，可重新点赞并正确计数")
    void testRelikeAfterUnlike() {
        blogController.likeBlog(blogId);
        blogController.unlikeBlog(blogId);
        Result<BlogLikeDTO> again = blogController.likeBlog(blogId);

        assertThat(again.getSuccess()).isTrue();
        assertThat(again.getData().getLikedCount()).isEqualTo(1);
        assertThat(likedCount(blogId)).isEqualTo(1);
        assertThat(messageCount(authorId, blogId)).isEqualTo(2);
    }

    @Test
    @DisplayName("点赞不存在的笔记返回 20001")
    void testLikeUnknownBlogRejected() {
        Result<BlogLikeDTO> result = blogController.likeBlog(998999999L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("20001");
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private Long createUser(String suffix) {
        // tb_user.nick_name 为 varchar(32)：使用自增序号而非 nanoTime，避免超长 Data truncation
        int seq = USER_SEQ.incrementAndGet();
        String phone = String.format("198%08d", seq % 100000000);
        // 自愈：清理可能残留的同 phone 用户
        jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id IN (SELECT id FROM tb_user WHERE phone = ?)", phone);
        jdbcTemplate.update("DELETE FROM tb_user WHERE phone = ?", phone);

        User user = new User();
        user.setPhone(phone);
        user.setNickName(TEST_NICK_PREFIX + suffix + "_" + seq);
        userMapper.insert(user);
        assertThat(user.getId()).isNotNull();
        return user.getId();
    }

    private Long createBlog(Long userId) {
        Blog blog = new Blog();
        blog.setShopId(SEED_SHOP_ID);
        blog.setUserId(userId);
        blog.setTitle(TEST_NICK_PREFIX + "title");
        blog.setImages("[]");
        blog.setContent(TEST_NICK_PREFIX + "content");
        blog.setLiked(0);
        blog.setComments(0);
        blogService.save(blog);
        assertThat(blog.getId()).isNotNull();
        return blog.getId();
    }

    private void loginAs(Long userId, String nickName) {
        UserDTO dto = new UserDTO();
        dto.setId(userId);
        dto.setNickName(nickName);
        dto.setIcon("");
        UserHolder.saveUser(dto);
    }

    private long likedCount(Long id) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(liked, 0) FROM tb_blog WHERE id = ?", Long.class, id);
        return value == null ? 0 : value;
    }

    private long messageCount(Long toUserId, Long relatedId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_message WHERE user_id = ? AND related_id = ? AND type = 1",
                Long.class, toUserId, relatedId);
        return count == null ? 0 : count;
    }

    private void cleanTestData() {
        if (blogId != null) {
            stringRedisTemplate.delete(RedisConstants.BLOG_LIKED_KEY + blogId);
            jdbcTemplate.update("DELETE FROM tb_blog_comments WHERE blog_id = ?", blogId);
            jdbcTemplate.update("DELETE FROM tb_blog WHERE id = ?", blogId);
        }
        if (authorId != null || likerId != null) {
            jdbcTemplate.update("DELETE FROM tb_message WHERE user_id IN (?, ?) OR sender_id IN (?, ?)",
                    authorId, likerId, authorId, likerId);
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id IN (?, ?)", authorId, likerId);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id IN (?, ?)", authorId, likerId);
        }
        // 兜底：清理昵称前缀残留（防止上次异常中断遗留）
        jdbcTemplate.update(
                "DELETE FROM tb_blog WHERE user_id IN (SELECT id FROM tb_user WHERE nick_name LIKE ?)",
                TEST_NICK_PREFIX + "%");
        jdbcTemplate.update(
                "DELETE FROM tb_user_info WHERE user_id IN (SELECT id FROM tb_user WHERE nick_name LIKE ?)",
                TEST_NICK_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_user WHERE nick_name LIKE ?", TEST_NICK_PREFIX + "%");
    }
}
