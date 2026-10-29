package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import jakarta.validation.constraints.Min;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关注关系控制器
 * <p>提供关注 / 取关、关注列表、粉丝列表等 REST API 接口</p>
 */
@Validated
@RestController
@RequestMapping("/follows")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 关注指定用户
     */
    @PostMapping("/{userId}")
    public Result follow(@PathVariable("userId") Long userId) {
        return followService.follow(userId);
    }

    /**
     * 取消关注指定用户
     */
    @DeleteMapping("/{userId}")
    public Result unfollow(@PathVariable("userId") Long userId) {
        return followService.unfollow(userId);
    }

    /**
     * 分页查询当前用户关注的人
     */
    @GetMapping("/following")
    public Result queryFollowing(
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        return followService.queryFollowing(current, size);
    }

    /**
     * 分页查询当前用户的粉丝
     */
    @GetMapping("/followers")
    public Result queryFollowers(
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        return followService.queryFollowers(current, size);
    }
}
