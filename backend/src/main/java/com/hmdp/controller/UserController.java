package com.hmdp.controller;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.LoginResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UpdateUserInfoDTO;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.UserInfo;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * 用户控制器
 * <p>
 * 提供用户相关的全套 REST API 接口，
 * 包括验证码发送、登录/登出、用户信息查询与更新、
 * 签到与连续签到天数统计等功能。
 * </p>
 */
@RestController
@RequestMapping("/users")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    /**
     * 发送手机验证码
     *
     * @param phone   手机号
     * @param session 会话对象
     * @return 发送结果
     */
    @PostMapping("/code")
    public Result<Void> sendCode(@RequestParam("phone") String phone, HttpSession session) {
        return userService.sendCode(phone, session);
    }

    /**
     * 开发/测试辅助：设置固定验证码，便于本地接口联调。
     * <p>
     * 仅用于本地开发环境：直接把指定 code 写入 Redis。
     * POST /users/code/mock?phone=13800138000&code=123456
     * </p>
     *
     * @param phone 手机号
     * @param code  验证码
     * @return 设置结果
     */
    @PostMapping("/code/mock")
    public Result<Void> mockCode(@RequestParam("phone") String phone,
                                 @RequestParam("code") String code) {
        return userService.mockCode(phone, code);
    }

    /**
     * 登录功能
     *
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     * @param session   会话对象
     * @return 登录结果，包含 token 等信息
     */
    @PostMapping("/login")
    public Result<LoginResultDTO> login(@Valid @RequestBody LoginFormDTO loginForm, HttpSession session) {
        return userService.login(loginForm, session);
    }

    /**
     * 登出功能
     *
     * @param request HTTP 请求对象，用于获取 token
     * @return 登出结果
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        return userService.logout(request.getHeader("authorization"));
    }

    /**
     * 更新用户信息
     *
     * @param dto     用户信息更新 DTO
     * @param request 当前请求（用于同步刷新 Redis 登录缓存）
     * @return 更新结果
     */
    @PutMapping("/info")
    public Result<Void> updateUserInfo(@Valid @RequestBody UpdateUserInfoDTO dto, HttpServletRequest request) {
        return userInfoService.updateUserInfo(dto, request.getHeader("authorization"));
    }

    /**
     * 获取当前登录用户信息
     *
     * @return 当前登录用户的 DTO 对象
     */
    @GetMapping("/me")
    public Result<UserDTO> getCurrentUser() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    /**
     * 根据用户 ID 获取用户信息详情
     *
     * @param userId 用户 ID
     * @return 用户详情信息
     */
    @GetMapping("/info/{userId}")
    public Result<UserInfo> queryUserInfo(@PathVariable("userId") Long userId) {
        return userInfoService.queryUserInfo(userId);
    }

    /**
     * 根据 ID 查询用户基本信息
     *
     * @param userId 用户 ID
     * @return 用户 DTO 对象
     */
    @GetMapping("/{userId}")
    public Result<UserDTO> queryUserById(@PathVariable("userId") Long userId) {
        return userService.queryUserDTO(userId);
    }

    /**
     * 用户签到
     *
     * @return 签到结果
     */
    @PostMapping("/signs")
    public Result<Void> signToday() {
        return userService.sign();
    }

    /**
     * 统计连续签到天数
     *
     * @return 连续签到天数
     */
    @GetMapping("/signs/count")
    public Result<Integer> countContinuousSignDays() {
        return userService.signCount();
    }

    /**
     * 查询今日是否已签到
     *
     * @return 今日签到状态
     */
    @GetMapping("/signs/today")
    public Result<Boolean> isSignedToday() {
        return userService.isSignedToday();
    }

}