package com.hmdp.mapper;

import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 关注关系 Mapper 接口
 */
public interface FollowMapper extends BaseMapper<Follow> {

    /**
     * 建立关注关系（幂等 upsert）。
     * <p>
     * 由于 tb_follow 使用逻辑删除 + 唯一索引 uk_user_follow，
     * 取消关注后重新关注不能直接 insert（会撞唯一键），
     * 因此这里用 {@code ON DUPLICATE KEY UPDATE} 把 is_deleted 复位为 0。
     * </p>
     *
     * @return 受影响行数（1=新插入，2=已存在并复活）
     */
    @Insert("INSERT INTO tb_follow (user_id, follow_user_id, is_deleted, create_time) "
            + "VALUES (#{userId}, #{targetUserId}, 0, NOW()) "
            + "ON DUPLICATE KEY UPDATE is_deleted = 0, create_time = NOW()")
    int upsertFollow(@Param("userId") Long userId, @Param("targetUserId") Long targetUserId);

    /**
     * 逻辑取消关注（仅当当前处于关注状态时才更新）。
     *
     * @return 受影响行数（1=取消成功，0=本就未关注）
     */
    @Update("UPDATE tb_follow SET is_deleted = 1 "
            + "WHERE user_id = #{userId} AND follow_user_id = #{targetUserId} AND is_deleted = 0")
    int logicalUnfollow(@Param("userId") Long userId, @Param("targetUserId") Long targetUserId);
}
