package com.hmdp.mapper;

import com.hmdp.entity.BlogComment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 博客评论 Mapper 接口
 */
@Mapper
public interface BlogCommentMapper extends BaseMapper<BlogComment> {
    // Custom methods can be added here if needed
}
