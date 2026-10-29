package com.hmdp.mapper;

import com.hmdp.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 用户详细信息 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，提供对 tb_user_info 表的 CRUD 操作。
 * 自定义 SQL 方法请在对应的 XML 文件中定义。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-24
 */
public interface UserInfoMapper extends BaseMapper<UserInfo> {

    /**
     * 原子累加用户的粉丝数 / 关注数。
     * <p>
     * 用户可能尚无 tb_user_info 记录，因此使用 upsert：不存在则插入，存在则累加。
     * 由于 fans / followee 为 UNSIGNED 列，MySQL 即使在 ON DUPLICATE KEY UPDATE
     * 场景下也会校验 VALUES 的取值，因此插入值用 {@code GREATEST(0, ...)} 夹取，
     * 避免负增量触发 "Out of range value"。更新时用 {@code COALESCE} 兼容历史 NULL。
     * </p>
     *
     * @param userId        目标用户id
     * @param fansDelta     粉丝数增量（可为负）
     * @param followeeDelta 关注数增量（可为负）
     * @return 受影响行数
     */
    @Insert("INSERT INTO tb_user_info (user_id, fans, followee) "
            + "VALUES (#{userId}, GREATEST(0, #{fansDelta}), GREATEST(0, #{followeeDelta})) "
            + "ON DUPLICATE KEY UPDATE "
            + "fans = GREATEST(0, COALESCE(fans, 0) + #{fansDelta}), "
            + "followee = GREATEST(0, COALESCE(followee, 0) + #{followeeDelta})")
    int addFollowCounts(@Param("userId") Long userId,
                        @Param("fansDelta") int fansDelta,
                        @Param("followeeDelta") int followeeDelta);
}

