package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.BlogCommentCreateRequest;
import com.hmdp.dto.BlogCommentDTO;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BlogComment;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogCommentMapper;
import com.hmdp.service.IBlogCommentService;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IMessageService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 笔记评论服务实现类
 * <p>
 * 评论写入 tb_blog_comments，同步维护 tb_blog.comments 计数，
 * 并在评论 / 回复时向笔记作者、被回复者发送站内消息。
 * </p>
 */
@Service
public class BlogCommentServiceImpl extends ServiceImpl<BlogCommentMapper, BlogComment> implements IBlogCommentService {

    @Resource
    private IBlogService blogService;

    @Resource
    private IUserService userService;

    @Resource
    private IMessageService messageService;

    @Override
    public Result queryByBlog(Long blogId, Integer current, Integer size) {
        if (blogId == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "笔记ID不能为空");
        }
        Page<BlogComment> page = query()
                .eq("blog_id", blogId)
                .and(w -> w.isNull("status").or().eq("status", 0))
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        List<BlogComment> records = page.getRecords();
        if (CollectionUtils.isEmpty(records)) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }

        // 被回复的评论 -> 其作者id，用于展示「回复了谁」
        List<Long> answerIds = records.stream()
                .map(BlogComment::getAnswerId)
                .filter(id -> id != null && id != 0L)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Long> answerAuthorMap = answerIds.isEmpty()
                ? Collections.emptyMap()
                : listByIds(answerIds).stream()
                    .collect(Collectors.toMap(BlogComment::getId, BlogComment::getUserId, (a, b) -> a));

        // 评论作者 + 被回复者 一起批量加载
        Set<Long> userIds = records.stream().map(BlogComment::getUserId).collect(Collectors.toSet());
        userIds.addAll(answerAuthorMap.values());
        Map<Long, User> userMap = userIds.isEmpty()
                ? Collections.emptyMap()
                : userService.listByIds(userIds).stream()
                    .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));

        List<BlogCommentDTO> list = records.stream()
                .map(c -> buildDTO(c, userMap, answerAuthorMap))
                .collect(Collectors.toList());
        return Result.ok(list, page.getTotal());
    }

    @Override
    public Result queryMine(Integer current, Integer size) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Page<BlogComment> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        List<BlogComment> records = page.getRecords();
        if (CollectionUtils.isEmpty(records)) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }
        List<Long> blogIds = records.stream()
                .map(BlogComment::getBlogId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, String> blogTitleMap = blogService.listByIds(blogIds).stream()
                .collect(Collectors.toMap(Blog::getId, Blog::getTitle, (a, b) -> a));
        List<BlogCommentDTO> list = records.stream()
                .map(c -> {
                    BlogCommentDTO dto = buildDTO(c, Collections.emptyMap(), Collections.emptyMap());
                    dto.setBlogTitle(blogTitleMap.get(c.getBlogId()));
                    return dto;
                })
                .collect(Collectors.toList());
        return Result.ok(list, page.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result create(Long blogId, BlogCommentCreateRequest request) {        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Blog blog = blogService.getById(blogId);
        if (blog == null) {
            return Result.fail(ErrorCode.BLOG_NOT_FOUND);
        }

        long parentId = request.getParentId() == null ? 0L : request.getParentId();
        BlogComment parent = null;
        if (parentId != 0L) {
            parent = getById(parentId);
            // 父评论不存在或不属于该笔记，则退化为一级评论
            if (parent == null || !blogId.equals(parent.getBlogId())) {
                parent = null;
                parentId = 0L;
            }
        }

        BlogComment comment = new BlogComment();
        comment.setBlogId(blogId);
        comment.setUserId(userId);
        comment.setContent(sanitize(request.getContent()));
        comment.setParentId(parentId);
        comment.setAnswerId(request.getAnswerId() != null ? request.getAnswerId() : parentId);
        comment.setLiked(0);
        comment.setStatus(0);
        save(comment);

        // 维护笔记评论数
        blogService.update()
                .setSql("comments = COALESCE(comments, 0) + 1")
                .eq("id", blogId)
                .update();

        // 站内消息：通知笔记作者；回复时额外通知被回复者
        // 正文只写动作，发送者昵称由前端消息中心按 sender 展示，避免重复
        String preview = preview(comment.getContent());
        messageService.send(blog.getUserId(), userId, 3, blogId, "评论了你的笔记：" + preview);
        if (parent != null) {
            messageService.send(parent.getUserId(), userId, 3, blogId, "回复了你的评论：" + preview);
        }
        return Result.ok(comment.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result delete(Long blogId, Long commentId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        BlogComment comment = getById(commentId);
        if (comment == null
                || !userId.equals(comment.getUserId())
                || !Objects.equals(blogId, comment.getBlogId())) {
            return Result.fail(ErrorCode.BLOG_COMMENT_NOT_FOUND);
        }
        long childCount = query().eq("parent_id", commentId).count();
        removeById(commentId);
        if (childCount > 0) {
            remove(Wrappers.<BlogComment>lambdaQuery().eq(BlogComment::getParentId, commentId));
        }
        // 回减笔记评论数（本人评论 + 其子回复）
        long removed = childCount + 1;
        blogService.update()
                .setSql("comments = GREATEST(0, COALESCE(comments, 0) - " + removed + ")")
                .eq("id", blogId)
                .update();
        return Result.ok();
    }

    private BlogCommentDTO buildDTO(BlogComment comment, Map<Long, User> userMap, Map<Long, Long> answerAuthorMap) {
        BlogCommentDTO dto = new BlogCommentDTO();
        dto.setId(comment.getId());
        dto.setBlogId(comment.getBlogId());
        dto.setUserId(comment.getUserId());
        User author = userMap.get(comment.getUserId());
        if (author != null) {
            dto.setNickName(author.getNickName());
            dto.setIcon(author.getIcon());
        }
        dto.setParentId(comment.getParentId());
        dto.setAnswerId(comment.getAnswerId());
        Long answerAuthorId = answerAuthorMap.get(comment.getAnswerId());
        User answerAuthor = answerAuthorId == null ? null : userMap.get(answerAuthorId);
        if (answerAuthor != null) {
            dto.setAnswerNickName(answerAuthor.getNickName());
        }
        dto.setContent(comment.getContent());
        dto.setCreateTime(comment.getCreateTime());
        return dto;
    }

    private String preview(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > 20 ? content.substring(0, 20) + "..." : content;
    }

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
