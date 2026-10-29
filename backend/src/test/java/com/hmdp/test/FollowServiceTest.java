package com.hmdp.test;

import com.hmdp.dto.FollowUserDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.Resource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 关注 / 粉丝功能测试
 *
 * 前置条件：
 *   - MySQL / Redis 需已启动
 *   - 测试自动创建两个测试用户，结束后清理
 *
 * 运行命令：
 *   cd backend && mvn test -Dtest=FollowServiceTest
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("关注与粉丝测试")
public class FollowServiceTest {

    private static final String TEST_NICK_PREFIX = "TestFollow_";
    /** 测试用户序号：保证 phone 唯一且长度可控 */
    private static final java.util.concurrent.atomic.AtomicInteger USER_SEQ =
            new java.util.concurrent.atomic.AtomicInteger();

    @Resource
    private IFollowService followService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /** 测试用户 id（insert 后由自增主键回填） */
    private Long userA;
    private Long userB;

    @BeforeEach
    void setUp() {
        cleanTestData();
        userA = createUser("A");
        userB = createUser("B");
        loginAs(userA, TEST_NICK_PREFIX + "A");
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
        cleanTestData();
    }

    @Test
    @DisplayName("重复关注幂等：关注数/粉丝数只累加一次")
    void testFollowIsIdempotent() {
        Result first = followService.follow(userB);
        Result second = followService.follow(userB);

        assertThat(first.getSuccess()).isTrue();
        assertThat(second.getSuccess()).isTrue();
        assertThat(activeFollowCount(userA, userB)).isEqualTo(1);
        assertThat(countOf(userA, "followee")).isEqualTo(1);
        assertThat(countOf(userB, "fans")).isEqualTo(1);
    }

    @Test
    @DisplayName("重复取关幂等：关注数/粉丝数只回减一次")
    void testUnfollowIsIdempotent() {
        followService.follow(userB);

        Result first = followService.unfollow(userB);
        Result second = followService.unfollow(userB);

        assertThat(first.getSuccess()).isTrue();
        assertThat(second.getSuccess()).isTrue();
        assertThat(activeFollowCount(userA, userB)).isZero();
        assertThat(countOf(userA, "followee")).isZero();
        assertThat(countOf(userB, "fans")).isZero();
    }

    @Test
    @DisplayName("取消关注后可再次关注（唯一索引 + 逻辑删除）")
    void testRefollowAfterUnfollow() {
        followService.follow(userB);
        followService.unfollow(userB);
        Result again = followService.follow(userB);

        assertThat(again.getSuccess()).isTrue();
        assertThat(activeFollowCount(userA, userB)).isEqualTo(1);
        assertThat(countOf(userA, "followee")).isEqualTo(1);
        assertThat(countOf(userB, "fans")).isEqualTo(1);
    }

    @Test
    @DisplayName("不能关注自己")
    void testFollowSelfRejected() {
        Result result = followService.follow(userA);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("10005");
    }

    @Test
    @DisplayName("关注不存在的用户返回 10007")
    void testFollowUnknownUserRejected() {
        Result result = followService.follow(998999999L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo("10007");
    }

    @Test
    @DisplayName("关注后可在「我的关注」中查询到，且 followed=true")
    void testQueryFollowing() {
        followService.follow(userB);

        Result result = followService.queryFollowing(1, 10);
        assertThat(result.getSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<FollowUserDTO> list = (List<FollowUserDTO>) result.getData();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getUserId()).isEqualTo(userB);
        assertThat(list.get(0).getFollowed()).isTrue();
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private Long createUser(String suffix) {
        // tb_user.nick_name 为 varchar(32)：使用自增序号而非 nanoTime，避免边界长度导致 Data truncation
        int seq = USER_SEQ.incrementAndGet();
        String phone = String.format("199%08d", seq % 100000000);
        jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id IN (SELECT id FROM tb_user WHERE phone = ?)", phone);
        jdbcTemplate.update("DELETE FROM tb_user WHERE phone = ?", phone);

        User user = new User();
        user.setPhone(phone);
        user.setNickName(TEST_NICK_PREFIX + suffix + "_" + seq);
        userMapper.insert(user);
        assertThat(user.getId()).isNotNull();
        return user.getId();
    }

    private void loginAs(Long userId, String nickName) {
        UserDTO dto = new UserDTO();
        dto.setId(userId);
        dto.setNickName(nickName);
        dto.setIcon("");
        UserHolder.saveUser(dto);
    }

    private long activeFollowCount(Long userId, Long followUserId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_follow WHERE user_id = ? AND follow_user_id = ? AND is_deleted = 0",
                Long.class, userId, followUserId);
        return count == null ? 0 : count;
    }

    private long countOf(Long userId, String column) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(" + column + ", 0) FROM tb_user_info WHERE user_id = ?",
                Long.class, userId);
        return value == null ? 0 : value;
    }

    private void cleanTestData() {
        if (userA != null && userB != null) {
            jdbcTemplate.update("DELETE FROM tb_follow WHERE user_id IN (?, ?) OR follow_user_id IN (?, ?)",
                    userA, userB, userA, userB);
            jdbcTemplate.update("DELETE FROM tb_message WHERE user_id IN (?, ?) OR sender_id IN (?, ?)",
                    userA, userB, userA, userB);
            // tb_user_info 对 tb_user 有外键，必须先删详情再删用户
            jdbcTemplate.update("DELETE FROM tb_user_info WHERE user_id IN (?, ?)", userA, userB);
            jdbcTemplate.update("DELETE FROM tb_user WHERE id IN (?, ?)", userA, userB);
        }
        // 兜底：清理昵称前缀残留（防止上次异常中断遗留）
        jdbcTemplate.update(
                "DELETE FROM tb_user_info WHERE user_id IN (SELECT id FROM tb_user WHERE nick_name LIKE ?)",
                TEST_NICK_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM tb_user WHERE nick_name LIKE ?", TEST_NICK_PREFIX + "%");
    }
}
