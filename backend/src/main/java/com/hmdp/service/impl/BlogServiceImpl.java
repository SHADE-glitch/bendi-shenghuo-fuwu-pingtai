package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.BlogSaveRequest;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComment;
import com.hmdp.mapper.BlogCommentMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 探店博客服务实现类
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private BlogCommentMapper blogCommentMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateBlog(Long blogId, BlogSaveRequest request) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Blog blog = getById(blogId);
        // 不存在或非本人笔记，统一按「博客不存在」应答，不泄露存在性
        if (blog == null || !userId.equals(blog.getUserId())) {
            return Result.fail(ErrorCode.BLOG_NOT_FOUND);
        }
        Blog update = new Blog();
        update.setId(blogId);
        update.setTitle(sanitize(request.getTitle()));
        update.setContent(sanitize(request.getContent()));
        update.setShopId(request.getShopId());
        // images 为 null 表示「不修改」；传空数组则清空为 []
        if (request.getImages() != null) {
            update.setImages(JSONUtil.toJsonStr(request.getImages()));
        }
        updateById(update);
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result deleteBlog(Long blogId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Blog blog = getById(blogId);
        if (blog == null || !userId.equals(blog.getUserId())) {
            return Result.fail(ErrorCode.BLOG_NOT_FOUND);
        }
        removeById(blogId);
        // 级联清理该笔记的评论，避免留下孤儿数据
        blogCommentMapper.delete(new LambdaQueryWrapper<BlogComment>().eq(BlogComment::getBlogId, blogId));
        // 清理点赞集合缓存
        stringRedisTemplate.delete(RedisConstants.BLOG_LIKED_KEY + blogId);
        return Result.ok();
    }

    /**
     * XSS 转义：仅转义尖括号，与发布笔记保持一致
     */
    private String sanitize(String input) {
        if (input == null) {
            return null;
        }
        return input.replace("<", "&lt;").replace(">", "&gt;");
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }
}
