package com.hmdp.mapper;

import com.hmdp.entity.BrowseHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * 浏览历史 Mapper 接口
 */
public interface BrowseHistoryMapper extends BaseMapper<BrowseHistory> {

    /**
     * 记录一次浏览（同一用户同一目标去重，仅刷新最近浏览时间）。
     *
     * @return 受影响行数
     */
    @Insert("INSERT INTO tb_browse_history (user_id, target_type, target_id, create_time, update_time) "
            + "VALUES (#{userId}, #{targetType}, #{targetId}, NOW(), NOW()) "
            + "ON DUPLICATE KEY UPDATE update_time = NOW()")
    int upsertHistory(@Param("userId") Long userId,
                      @Param("targetType") Integer targetType,
                      @Param("targetId") Long targetId);
}
