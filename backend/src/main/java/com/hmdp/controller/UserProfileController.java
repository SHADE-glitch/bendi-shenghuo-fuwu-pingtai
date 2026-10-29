package com.hmdp.controller;

import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.UserProfileDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.Min;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户主页控制器
 * <p>
 * 补「社交闭环」缺口：关注/粉丝/评论里的用户可点进对方主页。
 * 主页本身与店铺详情一致，允许匿名浏览；已登录时会额外返回「我是否已关注 TA」。
 * </p>
 */
@Validated
@RestController
@RequestMapping("/users")
public class UserProfileController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private IBlogService blogService;

    @Resource
    private IFollowService followService;

    /**
     * 查询用户主页聚合信息（基础资料 + 统计 + 是否已关注）
     */
    @GetMapping("/{userId}/profile")
    public Result<UserProfileDTO> profile(@PathVariable("userId") Long userId) {
        if (userId == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "用户ID不能为空");
        }
        User user = userService.getById(userId);
        if (user == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "用户不存在");
        }

        UserProfileDTO dto = new UserProfileDTO();
        dto.setId(user.getId());
        dto.setNickName(user.getNickName());
        dto.setIcon(user.getIcon());

        UserInfo info = userInfoService.getById(userId);
        if (info != null) {
            dto.setIntroduce(info.getIntroduce());
            dto.setGender(info.getGender());
            dto.setCredits(info.getCredits());
            dto.setLevel(info.getLevel());
            dto.setFollowee(info.getFollowee());
            dto.setFans(info.getFans());
        }

        dto.setBlogCount(blogService.query().eq("user_id", userId).count());

        UserDTO current = UserHolder.getUser();
        boolean followedByMe = current != null
                && !current.getId().equals(userId)
                && followService.hasFollowed(current.getId(), userId);
        dto.setFollowedByMe(followedByMe);

        return Result.ok(dto);
    }

    /**
     * 分页查询某用户发布的笔记
     */
    @GetMapping("/{userId}/blogs")
    public Result<List<Blog>> blogs(@PathVariable("userId") Long userId,
                                    @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
                                    @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        if (userId == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "用户ID不能为空");
        }
        Page<Blog> page = blogService.query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        Result<List<Blog>> result = Result.ok(page.getRecords());
        result.setTotal(page.getTotal());
        return result;
    }
}
