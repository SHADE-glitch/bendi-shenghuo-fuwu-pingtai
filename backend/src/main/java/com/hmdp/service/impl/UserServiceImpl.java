package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RadixUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.LoginResultDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 用户服务实现类
 * <p>
 * 提供用户手机号验证码登录、自动注册、Token 管理等功能，
 * 并基于 Redis BitMap 实现用户签到和连续签到天数统计。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 是否启用 Mock 功能（仅用于开发环境）
     */
    @Value("${hmdp.mock-enabled:false}")
    private boolean mockEnabled;

    /**
     * 向指定手机号发送 6 位数字登录验证码，并存入 Redis（有效期 2 分钟）
     * <p>
     * P1-1：发送侧频率限制。分钟级冷却（SETNX）+ 小时级配额（INCR）双闸门，
     * 均在生成验证码之前判定，超限直接拒绝、不发放；新验证码发放时重置失败计数。
     * </p>
     *
     * @param phone   目标手机号
     * @param session HTTP 会话（兼容传统模式，主要逻辑依赖 Redis）
     * @return 发送结果
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号合法性
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail(com.hmdp.dto.ErrorCode.PHONE_INVALID, "手机号格式不正确");
        }
        // 2. 发送频率限制（P1-1）：先过分钟级冷却，再过小时级配额，任一超限即拒绝。
        //    没有这两道闸门时，6 位验证码（空间 100 万）可被「反复重发 + 高速枚举」击穿。
        Boolean cooldown = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOGIN_CODE_SEND_LIMIT_KEY + phone, "1",
                        RedisConstants.LOGIN_CODE_SEND_LIMIT_TTL, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(cooldown)) {
            return Result.fail(com.hmdp.dto.ErrorCode.CODE_SEND_TOO_FREQUENT);
        }
        Long sentThisHour = stringRedisTemplate.opsForValue()
                .increment(LOGIN_CODE_SEND_COUNT_KEY + phone);
        if (sentThisHour != null && sentThisHour == 1L) {
            // 首次计数才设 TTL：窗口从本小时第一次发送起算，之后每次发送顺延不到窗口外
            stringRedisTemplate.expire(LOGIN_CODE_SEND_COUNT_KEY + phone,
                    RedisConstants.LOGIN_CODE_SEND_COUNT_TTL, TimeUnit.SECONDS);
        }
        if (sentThisHour != null && sentThisHour > RedisConstants.LOGIN_CODE_SEND_MAX_PER_HOUR) {
            return Result.fail(com.hmdp.dto.ErrorCode.CODE_SEND_TOO_FREQUENT);
        }
        // 3. 生成 6 位随机验证码
        String code = RandomUtil.randomNumbers(6);
        // 4. 保存验证码到 Redis，设置过期时间
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code,
                RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 5. 新验证码发放即重置失败尝试计数：上一个码的失败记录不连坐新码
        stringRedisTemplate.delete(LOGIN_CODE_ATTEMPT_KEY + phone);
        // 6. 记录发送日志：绝不打印验证码本体。
        //    旧实现把 6 位明文码打进 INFO（2026-10-06 审查 F-6），任何有日志读权限的人
        //    （日志平台 / 运维 / 采集链路）都能直接登录任意手机号 —— 这是账号接管路径。
        //    现在只保留手机号与码长度，可观测性不丢，敏感值不落盘。
        log.info("手机号 {} 的验证码已写入 Redis（{} 位，TTL {} 分钟）",
                maskPhone(phone), code.length(), RedisConstants.LOGIN_CODE_TTL);
        return Result.ok();
    }

    /**
     * 手机号脱敏：保留前 3 位与后 4 位，中间打码。
     * <p>
     * 用于日志场景。既能按前缀/尾号定位问题，又不会让读日志的人拿到完整号码。
     * </p>
     *
     * @param phone 手机号
     * @return 脱敏后的手机号；长度不足时原样返回
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 开发/测试辅助：设置固定验证码，便于本地接口联调
     * <p>
     * 仅用于本地开发环境：直接把指定 code 写入 Redis。
     * </p>
     *
     * @param phone 手机号
     * @param code  验证码
     * @return 设置结果
     */
    @Override
    public Result mockCode(String phone, String code) {
        // 生产环境禁用 Mock 功能
        if (!mockEnabled) {
            return Result.fail("Mock code disabled in production environment");
        }
        
        if (phone == null || phone.trim().isEmpty()) {
            return Result.fail("手机号不能为空");
        }
        if (code == null || code.trim().isEmpty()) {
            return Result.fail("验证码不能为空");
        }
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code);
        return Result.ok();
    }

    /**
     * 用户登录/自动注册
     * 流程：校验手机号 -> 校验验证码 -> 查询/创建用户 -> 生成 Token -> 存入 Redis -> 返回凭证
     *
     * @param loginForm 包含手机号和验证码的登录表单
     * @param session   HTTP 会话
     * @return 包含 Token 和用户信息的登录结果
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail(com.hmdp.dto.ErrorCode.PHONE_INVALID, "手机号格式错误！");
        }
        // 2. 从 Redis 获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            // P1-1：失败也消耗尝试次数。达到上限立即作废当前验证码并要求重新发送，
            // 把「一个码可被无限枚举」收敛为「一个码最多 5 次校验」。
            // 失败响应不区分「码错 / 码已过期」，不向枚举攻击泄漏验证码状态。
            Long attempts = stringRedisTemplate.opsForValue().increment(LOGIN_CODE_ATTEMPT_KEY + phone);
            if (attempts != null && attempts == 1L) {
                stringRedisTemplate.expire(LOGIN_CODE_ATTEMPT_KEY + phone,
                        RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
            }
            if (attempts != null && attempts >= RedisConstants.LOGIN_CODE_MAX_ATTEMPTS) {
                stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
                stringRedisTemplate.delete(LOGIN_CODE_ATTEMPT_KEY + phone);
                return Result.fail(com.hmdp.dto.ErrorCode.LOGIN_CODE_ATTEMPTS_EXCEEDED);
            }
            return Result.fail(com.hmdp.dto.ErrorCode.LOGIN_CODE_INVALID, "验证码错误");
        }
        // 2.1 验证码一次性：校验通过即删除。否则同一码在剩余 TTL（2 分钟）内
        //     可被反复登录，短信验证码退化成"短期口令"。
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        // 2.2 登录成功同步清除失败计数，避免历史失败记录影响下一次登录
        stringRedisTemplate.delete(LOGIN_CODE_ATTEMPT_KEY + phone);

        // 3. 根据手机号查询用户
        User user = query().eq("phone", phone).one();

        // 4. 用户不存在则自动创建并保存（自动注册）
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        // 5. 保存用户信息到 Redis
        // 5.1 生成随机 Token 作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 5.2 将 User 转为 DTO 并过滤敏感信息
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 5.3 转为 Map 存储到 Redis Hash 中，所有字段转为 String 以适配 StringRedisTemplate
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 5.4 存储数据到 Redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 5.5 设置 Token 有效期（通常为 30 分钟）
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);

        // 6. 返回结果（Token + 用户信息，减少前端后续请求次数）
        LoginResultDTO result = new LoginResultDTO();
        result.setToken(token);
        result.setUser(userDTO);
        return Result.ok(result);
    }

    /**
     * 用户登出（删除 Redis 中的登录缓存）
     *
     * @param token 登录凭证
     * @return 登出结果
     */
    @Override
    public Result logout(String token) {
        if (token != null && !token.isEmpty()) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        return Result.ok();
    }

    /**
     * 根据用户 ID 查询用户基本信息
     *
     * @param userId 用户 ID
     * @return 用户 DTO 结果
     */
    @Override
    public Result queryUserDTO(Long userId) {
        User user = getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    /**
     * 内部辅助：根据手机号创建新用户
     *
     * @param phone 手机号
     * @return 新建的用户实体
     */
    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 显式初始化角色为普通用户(0)，与 tb_user.role 的 DB DEFAULT 对齐。
        // 否则内存实体 role 为 null，登录时 beanToMap 的 fieldValueEditor 会对其调用
        // toString() 抛 NPE，导致新用户首次登录返回 99999。
        user.setRole(0);
        this.save(user);
        return user;
    }

    /**
     * 用户每日签到
     * 基于 Redis BitMap 实现，Key 格式为：sign:{userId}:{yyyyMM}
     *
     * @return 签到成功结果
     */
    @Override
    public Result sign() {
        // 1. 获取当前用户 ID
        Long userId = UserHolder.getUser().getId();
        // 2. 获取当前日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接 Redis Key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. 获取今天是本月第几天（从 1 开始）
        int dayOfMonth = now.getDayOfMonth();
        // 5. 设置 BitMap 对应位为 1 (offset 从 0 开始)
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 统计当前用户本月截至今日的连续签到天数
     *
     * @return 连续签到天数
     */
    @Override
    public Result signCount() {
        // 1. 获取当前用户 ID
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接 Key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4. 获取今天是第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5. 获取本月截止今日的所有签到记录（返回长整型数字）
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        // 6. 循环遍历二进制低位统计连续的 1
        int count = 0;
        while (true) {
            // 与 1 做与运算得到最后一位，若为 0 则连续签到中断
            if ((num & 1) == 0) {
                break;
            } else {
                count++;
            }
            // 无符号右移抛弃最后一位
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 查询当前用户今日是否已签到
     *
     * @return 今日签到状态
     */
    @Override
    public Result isSignedToday() {
        // 1. 获取当前用户 ID
        Long userId = UserHolder.getUser().getId();
        // 2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3. 拼接 Key
        String key = USER_SIGN_KEY + userId + now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        // 4. 查询今日对应位是否为 1（offset 从 0 开始）
        Boolean signed = stringRedisTemplate.opsForValue().getBit(key, now.getDayOfMonth() - 1);
        return Result.ok(Boolean.TRUE.equals(signed));
    }

}
