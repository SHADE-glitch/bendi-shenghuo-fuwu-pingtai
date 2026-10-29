package com.hmdp.controller;

import com.hmdp.dto.BlogCommentCreateRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IBlogCommentService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 笔记评论控制器
 * <p>提供笔记评论的查询、发布与删除接口，路径均需登录。</p>
 */
@Validated
@RestController
@RequestMapping("/blogs")
public class BlogCommentController {

    @Resource
    private IBlogCommentService blogCommentService;

    /**
     * 分页查询某笔记的评论（含作者信息）
     */
    @GetMapping("/{blogId}/comments")
    public Result queryByBlog(
            @PathVariable("blogId") Long blogId,
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        return blogCommentService.queryByBlog(blogId, current, size);
    }

    /**
     * 分页查询当前用户发表的评论（含笔记标题）
     * <p>路径为字面量 {@code /comments/mine}，不会与 {@code /{blogId}/comments} 冲突。</p>
     */
    @GetMapping("/comments/mine")
    public Result queryMine(
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        return blogCommentService.queryMine(current, size);
    }

    /**
     * 发布评论 / 回复
     */
    @PostMapping("/{blogId}/comments")
    public Result create(@PathVariable("blogId") Long blogId,
                         @Valid @RequestBody BlogCommentCreateRequest request) {
        return blogCommentService.create(blogId, request);
    }

    /**
     * 删除本人的评论（连同子回复）
     */
    @DeleteMapping("/{blogId}/comments/{commentId}")
    public Result delete(@PathVariable("blogId") Long blogId,
                         @PathVariable("commentId") Long commentId) {
        return blogCommentService.delete(blogId, commentId);
    }
}
