package com.hmdp.controller;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.BlogDetailDTO;
import com.hmdp.dto.BlogLikeDTO;
import com.hmdp.dto.BlogSaveRequest;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComment;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IMessageService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 探店博客控制器
 * <p>提供博客的发布、查询、点赞等 REST API 接口</p>
 */
@Validated
@RestController
@RequestMapping("/blogs")
public class BlogController {
    
    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Resource
    private IShopService shopService;

    @Resource
    private BlogCommentMapper blogCommentMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IMessageService messageService;
    
    /**
     * 发布探店博客
     */
    @PostMapping
    public Result<Long> createBlog(@Valid @RequestBody BlogSaveRequest request) {
        Blog blog = new Blog();
        blog.setTitle(sanitize(request.getTitle()));
        blog.setContent(sanitize(request.getContent()));
        blog.setImages(request.getImages() != null ?
            JSONUtil.toJsonStr(request.getImages()) : "[]");
        blog.setShopId(request.getShopId());
        
        // Set current user ID (from ThreadLocal)
        blog.setUserId(UserHolder.getUser().getId());
        
        blogService.save(blog);
        return Result.ok(blog.getId());
    }
    
    /**
     * 获取博客详情（包含作者、店铺、评论）
     */
    @GetMapping("/{blogId}")
    public Result<BlogDetailDTO> getBlogDetail(@PathVariable Long blogId) {
        Blog blog = blogService.getById(blogId);
        if (blog == null) {
            return Result.fail(ErrorCode.BLOG_NOT_FOUND);
        }
        
        BlogDetailDTO dto = new BlogDetailDTO();
        dto.setId(blog.getId());
        dto.setShopId(blog.getShopId());
        dto.setUserId(blog.getUserId());
        dto.setTitle(blog.getTitle());
        dto.setContent(blog.getContent());
        dto.setLiked(blog.getLiked());
        dto.setComments(blog.getComments());
        dto.setCreateTime(blog.getCreateTime());

        // 当前用户是否已点赞
        UserDTO current = UserHolder.getUser();
        boolean likedByMe = current != null
                && Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                        .isMember(RedisConstants.BLOG_LIKED_KEY + blogId, current.getId().toString()));
        dto.setLikedByMe(likedByMe);
        
        // 图片（JSON 字符串 -> List）
        dto.setImages(parseImages(blog.getImages()));
        
        // 作者信息
        User author = userService.getById(blog.getUserId());
        if (author != null) {
            UserDTO authorDTO = new UserDTO();
            authorDTO.setId(author.getId());
            authorDTO.setNickName(author.getNickName());
            authorDTO.setIcon(author.getIcon());
            dto.setAuthor(authorDTO);
        }
        
        // 店铺信息
        Shop shop = shopService.getById(blog.getShopId());
        if (shop != null) {
            dto.setShopName(shop.getName());
        }
        
        // 评论（仅顶层评论，最新 10 条）
        List<BlogComment> comments = blogCommentMapper.selectList(
            new LambdaQueryWrapper<BlogComment>()
                .eq(BlogComment::getBlogId, blogId)
                .eq(BlogComment::getParentId, 0L)
                .orderByDesc(BlogComment::getCreateTime)
                .last("LIMIT 10"));
        List<String> commentContents = comments.stream()
                .map(BlogComment::getContent)
                .collect(Collectors.toList());
        dto.setCommentContents(commentContents);
        
        return Result.ok(dto);
    }
    
    /**
     * 按店铺查询博客列表
     */
    @GetMapping("/shop/{shopId}")
    public Result<List<Blog>> getBlogsByShop(@PathVariable Long shopId) {
        List<Blog> blogs = blogService
            .query()
            .eq("shop_id", shopId)
            .orderByDesc("create_time")
            .list();
        return Result.ok(blogs);
    }
    
    /**
     * 点赞笔记（按用户幂等，重复点赞不重复计数）
     * <p>
     * 使用 Redis Set {@code blog:liked:{blogId}} 记录点赞用户，
     * 仅当 SADD 返回 1（首次点赞）时才累加计数并发送站内消息。
     * </p>
     */
    @PostMapping("/{blogId}/like")
    public Result<BlogLikeDTO> likeBlog(@PathVariable Long blogId) {
        Blog blog = blogService.getById(blogId);
        if (blog == null) {
            return Result.fail(ErrorCode.BLOG_NOT_FOUND);
        }
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blogId;
        Long added = stringRedisTemplate.opsForSet().add(key, userId.toString());
        if (added != null && added > 0) {
            blogService.update()
                .setSql("liked = COALESCE(liked, 0) + 1")
                .eq("id", blogId)
                .update();
            // 站内信正文只写动作，发送者昵称由前端消息中心按 sender 展示，避免重复
            messageService.send(blog.getUserId(), userId, 1, blogId, "赞了你的笔记");
        }
        return Result.ok(buildLikeResult(blogId, true));
    }

    /**
     * 取消点赞笔记（按用户幂等，未点赞时静默成功）
     */
    @DeleteMapping("/{blogId}/like")
    public Result<BlogLikeDTO> unlikeBlog(@PathVariable Long blogId) {
        Blog blog = blogService.getById(blogId);
        if (blog == null) {
            return Result.fail(ErrorCode.BLOG_NOT_FOUND);
        }
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + blogId;
        Long removed = stringRedisTemplate.opsForSet().remove(key, userId.toString());
        if (removed != null && removed > 0) {
            blogService.update()
                .setSql("liked = GREATEST(0, COALESCE(liked, 0) - 1)")
                .eq("id", blogId)
                .update();
        }
        return Result.ok(buildLikeResult(blogId, false));
    }

    private BlogLikeDTO buildLikeResult(Long blogId, boolean liked) {
        Blog fresh = blogService.getById(blogId);
        BlogLikeDTO dto = new BlogLikeDTO();
        dto.setLiked(liked);
        dto.setLikedCount(fresh != null ? fresh.getLiked() : null);
        return dto;
    }
    
    /**
     * 获取当前用户的博客列表
     */
    @GetMapping("/mine")
    public Result<List<Blog>> getMyBlogs(
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        
        Page<Blog> page = blogService
            .query()
            .eq("user_id", UserHolder.getUser().getId())
            .orderByDesc("create_time")
            .page(new Page<>(current, size));
        Result<List<Blog>> result = Result.ok(page.getRecords());
        result.setTotal(page.getTotal());
        return result;
    }

    /**
     * 解析博客图片 JSON 字符串为列表
     */
    private List<String> parseImages(String imagesJson) {
        if (imagesJson == null || imagesJson.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<String> list = JSONUtil.toList(imagesJson, String.class);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * XSS 转义：将 < 与 > 转义为 HTML 实体，防止脚本注入。
     * 仅转义尖括号，不影响中文、Markdown、普通链接等纯文本内容。
     */
    private static String sanitize(String input) {
        if (input == null) {
            return null;
        }
        return input.replace("<", "&lt;").replace(">", "&gt;");
    }
}
