package com.hmdp.controller;

import com.hmdp.dto.BlogSaveRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IBlogService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 笔记内容管理控制器
 * <p>
 * 提供「我发布的笔记」的编辑与删除能力，仅作者本人可操作。
 * 发布与查询仍由 {@link BlogController} 负责，此处只补内容管理缺口。
 * </p>
 */
@RestController
@RequestMapping("/blogs")
public class BlogManageController {

    @Resource
    private IBlogService blogService;

    /**
     * 编辑我的笔记（仅作者本人）
     */
    @PutMapping("/{blogId}")
    public Result<Void> updateBlog(@PathVariable("blogId") Long blogId,
                                   @Valid @RequestBody BlogSaveRequest request) {
        return blogService.updateBlog(blogId, request);
    }

    /**
     * 删除我的笔记（仅作者本人，级联清理评论与点赞缓存）
     */
    @DeleteMapping("/{blogId}")
    public Result<Void> deleteBlog(@PathVariable("blogId") Long blogId) {
        return blogService.deleteBlog(blogId);
    }
}
