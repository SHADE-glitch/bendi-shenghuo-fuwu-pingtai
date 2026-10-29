package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.UpdateUserInfoDTO;
import com.hmdp.entity.User;
import com.hmdp.entity.UserInfo;
import com.hmdp.mapper.UserInfoMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserInfoService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * 用户信息服务实现类
 * <p>
 * 负责用户基本信息（昵称、头像）和用户详细资料（城市、简介、性别、生日）的更新。
 * 同时维护 tb_user 和 tb_user_info 两张表的数据一致性，对用户简介字段进行 XSS 转义处理。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 同时写 tb_user 与 tb_user_info：两次写入必须同成同败，
     * 否则会出现「昵称改了、资料没改」的半更新状态。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateUserInfo(UpdateUserInfoDTO dto, String token) {
        Long userId = UserHolder.getUser() != null ? UserHolder.getUser().getId() : null;
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        if (dto == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "更新内容不能为空");
        }
        // 1. 更新 tb_user
        if (dto.getNickName() != null || dto.getIcon() != null) {
            // Nickname must not be empty when provided
            User user = new User();
            user.setId(userId);
            if (dto.getNickName() != null) {
                String nn = dto.getNickName().trim();
                if (nn.isEmpty()) {
                    return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "昵称不能为空");
                }
                user.setNickName(nn);
            }
            if (dto.getIcon() != null) user.setIcon(dto.getIcon());
            userMapper.updateById(user);
        }
        // 2. 更新/新增 tb_user_info
        UserInfo info = this.getById(userId);
        // 记录「是否已存在」：重复查库会在并发下把「本来有」判成「没有」，
        // 随后 save() 撞主键，把一次正常的资料更新变成 99999。
        boolean exists = info != null;
        if (!exists) {
            info = new UserInfo();
            info.setUserId(userId);
        }
        if (dto.getCity() != null) info.setCity(dto.getCity());
        if (dto.getIntroduce() != null) info.setIntroduce(sanitize(dto.getIntroduce()));
        if (dto.getGender() != null) info.setGender(dto.getGender());
        if (dto.getBirthday() != null) info.setBirthday(dto.getBirthday());
        if (exists) {
            this.updateById(info);
        } else {
            this.save(info);
        }
        // 3. 同步刷新 Redis 登录缓存中的昵称/头像，避免 /users/me 读到旧值。
        //    必须等事务提交：否则回滚后缓存里是新值、DB 里是旧值，缓存与库长期不一致。
        registerAfterCommit(() -> refreshLoginCache(token, dto));
        return Result.ok();
    }

    /** 事务提交后执行；无事务上下文时立即执行。 */
    private void registerAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * 同步更新 Redis 登录缓存中已变更的字段
     */
    private void refreshLoginCache(String token, UpdateUserInfoDTO dto) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String key = LOGIN_USER_KEY + token;
        if (dto.getNickName() != null) {
            stringRedisTemplate.opsForHash().put(key, "nickName", dto.getNickName().trim());
        }
        if (dto.getIcon() != null) {
            stringRedisTemplate.opsForHash().put(key, "icon", dto.getIcon());
        }
    }

    @Override
    public Result queryUserInfo(Long userId) {
        UserInfo info = getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }

    private String sanitize(String input) {
        if (input == null) return null;
        return input.replaceAll("<", "&lt;").replaceAll(">", "&gt;");
    }
}