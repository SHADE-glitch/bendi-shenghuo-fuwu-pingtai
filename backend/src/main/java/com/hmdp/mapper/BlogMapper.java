package com.hmdp.mapper;

import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 探店博客 Mapper 接口
 */
@Mapper
public interface BlogMapper extends BaseMapper<Blog> {
    // Custom methods can be added here if needed
}
