package com.hmdp.service;

import com.hmdp.dto.BlogCommentCreateRequest;
import com.hmdp.dto.Result;
import com.hmdp.entity.BlogComment;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 笔记评论服务接口
 */
public interface IBlogCommentService extends IService<BlogComment> {

    /**
     * 分页查询某笔记的评论（含作者信息，按时间倒序）
     */
    Result queryByBlog(Long blogId, Integer current, Integer size);

    /**
     * 分页查询当前用户发表的评论（含笔记标题）
     */
    Result queryMine(Integer current, Integer size);

    /**
     * 发布评论 / 回复，并维护笔记评论数与站内消息
     */
    Result create(Long blogId, BlogCommentCreateRequest request);

    /**
     * 删除本人的评论（连同其子回复），并回减笔记评论数
     */
    Result delete(Long blogId, Long commentId);
}
