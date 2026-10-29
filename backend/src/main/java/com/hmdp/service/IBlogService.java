package com.hmdp.service;

import com.hmdp.dto.BlogSaveRequest;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 探店博客服务接口
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 编辑我的笔记（仅作者本人，越权按「博客不存在」应答）
     *
     * @param blogId  笔记id
     * @param request 标题/内容/图片/店铺
     * @return 更新结果
     */
    Result updateBlog(Long blogId, BlogSaveRequest request);

    /**
     * 删除我的笔记（仅作者本人，级联清理评论与点赞集合）
     *
     * @param blogId 笔记id
     * @return 删除结果
     */
    Result deleteBlog(Long blogId);
}
