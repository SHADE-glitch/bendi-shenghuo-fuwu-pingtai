package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.FollowUserDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserInfoMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IMessageService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 关注关系服务实现类
 * <p>
 * 负责关注 / 取关以及关注、粉丝列表查询。关注关系写入 tb_follow，
 * 并同步维护 tb_user_info 的粉丝数 / 关注数；关注成功时向被关注者发送站内消息。
 * </p>
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private FollowMapper followMapper;

    @Resource
    private IUserService userService;

    @Resource
    private UserInfoMapper userInfoMapper;

    @Resource
    private IMessageService messageService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result follow(Long targetUserId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        if (targetUserId == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "关注目标不能为空");
        }
        if (targetUserId.equals(userId)) {
            return Result.fail(ErrorCode.FOLLOW_SELF_NOT_ALLOWED);
        }
        User target = userService.getById(targetUserId);
        if (target == null) {
            return Result.fail(ErrorCode.FOLLOW_INVALID_TARGET);
        }
        // 幂等：已关注则直接返回成功，不重复计数、不重复发消息
        if (isFollowing(userId, targetUserId)) {
            return Result.ok();
        }
        followMapper.upsertFollow(userId, targetUserId);
        // 我关注数 +1，对方粉丝数 +1
        userInfoMapper.addFollowCounts(userId, 0, 1);
        userInfoMapper.addFollowCounts(targetUserId, 1, 0);

        // 站内信正文只写动作，发送者昵称由前端消息中心按 sender 展示，避免重复
        messageService.send(targetUserId, userId, 2, userId, "关注了你");
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result unfollow(Long targetUserId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        if (targetUserId == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "关注目标不能为空");
        }
        // 幂等：本就未关注则直接返回成功
        if (followMapper.logicalUnfollow(userId, targetUserId) == 0) {
            return Result.ok();
        }
        // 我关注数 -1，对方粉丝数 -1
        userInfoMapper.addFollowCounts(userId, 0, -1);
        userInfoMapper.addFollowCounts(targetUserId, -1, 0);
        return Result.ok();
    }

    @Override
    public Result queryFollowing(Integer current, Integer size) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Page<Follow> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        List<Follow> records = page.getRecords();
        if (CollectionUtils.isEmpty(records)) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }
        // 「我的关注」列表里，followed 恒为 true
        List<Long> targetIds = records.stream().map(Follow::getFollowUserId).collect(Collectors.toList());
        Map<Long, User> userMap = loadUsers(targetIds);
        List<FollowUserDTO> list = records.stream()
                .map(f -> buildItem(f.getFollowUserId(), userMap.get(f.getFollowUserId()), true, f))
                .collect(Collectors.toList());
        return Result.ok(list, page.getTotal());
    }

    @Override
    public Result queryFollowers(Integer current, Integer size) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Page<Follow> page = query()
                .eq("follow_user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        List<Follow> records = page.getRecords();
        if (CollectionUtils.isEmpty(records)) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }
        List<Long> fanIds = records.stream().map(Follow::getUserId).collect(Collectors.toList());
        Map<Long, User> userMap = loadUsers(fanIds);
        // 计算「我是否已回关」这些粉丝
        Set<Long> followedIds = query()
                .eq("user_id", userId)
                .in("follow_user_id", fanIds)
                .list()
                .stream()
                .map(Follow::getFollowUserId)
                .collect(Collectors.toSet());
        List<FollowUserDTO> list = records.stream()
                .map(f -> buildItem(f.getUserId(), userMap.get(f.getUserId()), followedIds.contains(f.getUserId()), f))
                .collect(Collectors.toList());
        return Result.ok(list, page.getTotal());
    }

    @Override
    public boolean hasFollowed(Long userId, Long targetUserId) {
        if (userId == null || targetUserId == null || userId.equals(targetUserId)) {
            return false;
        }
        return isFollowing(userId, targetUserId);
    }

    private boolean isFollowing(Long userId, Long targetUserId) {
        return query()
                .eq("user_id", userId)
                .eq("follow_user_id", targetUserId)
                .count() > 0;
    }

    private Map<Long, User> loadUsers(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyMap();
        }
        return userService.listByIds(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
    }

    private FollowUserDTO buildItem(Long targetUserId, User user, boolean followed, Follow relation) {
        FollowUserDTO dto = new FollowUserDTO();
        dto.setUserId(targetUserId);
        if (user != null) {
            dto.setNickName(user.getNickName());
            dto.setIcon(user.getIcon());
        }
        dto.setFollowed(followed);
        dto.setCreateTime(relation.getCreateTime());
        return dto;
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }
}
