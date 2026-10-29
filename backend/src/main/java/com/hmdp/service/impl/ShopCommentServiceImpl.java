package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopCommentCreateRequest;
import com.hmdp.dto.ShopCommentDTO;
import com.hmdp.dto.ShopCommentSummaryDTO;
import com.hmdp.dto.ShopCommentUpdateRequest;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopComment;
import com.hmdp.entity.User;
import com.hmdp.mapper.ShopCommentMapper;
import com.hmdp.service.IShopCommentService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商铺评价服务实现类
 * <p>
 * 评价数据写入 tb_shop_comments；评分汇总实时聚合，
 * 不修改 tb_shop 的计数 / 评分字段，也不触碰店铺缓存。
 * </p>
 */
@Service
public class ShopCommentServiceImpl extends ServiceImpl<ShopCommentMapper, ShopComment> implements IShopCommentService {

    @Resource
    private ShopCommentMapper shopCommentMapper;

    @Resource
    private IUserService userService;

    @Resource
    private IShopService shopService;

    @Override
    public Result create(ShopCommentCreateRequest request) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Shop shop = shopService.getById(request.getShopId());
        if (shop == null) {
            return Result.fail(ErrorCode.SHOP_NOT_FOUND);
        }
        ShopComment comment = new ShopComment()
                .setShopId(request.getShopId())
                .setUserId(userId)
                .setContent(sanitize(request.getContent()))
                .setRating(request.getRating());
        save(comment);
        return Result.ok(comment.getId());
    }

    @Override
    public Result queryByShop(Long shopId, Integer current, Integer size) {
        if (shopId == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "店铺ID不能为空");
        }
        Page<ShopComment> page = query()
                .eq("shop_id", shopId)
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        List<ShopComment> records = page.getRecords();
        if (CollectionUtils.isEmpty(records)) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }
        Map<Long, User> userMap = loadUsers(records.stream().map(ShopComment::getUserId).collect(Collectors.toList()));
        List<ShopCommentDTO> list = records.stream()
                .map(c -> buildDTO(c, userMap.get(c.getUserId()), null))
                .collect(Collectors.toList());
        return Result.ok(list, page.getTotal());
    }

    @Override
    public Result queryMine(Integer current, Integer size) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Page<ShopComment> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        List<ShopComment> records = page.getRecords();
        if (CollectionUtils.isEmpty(records)) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }
        List<Long> shopIds = records.stream()
                .map(ShopComment::getShopId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Shop> shopMap = shopService.listByIds(shopIds).stream()
                .collect(Collectors.toMap(Shop::getId, Function.identity(), (a, b) -> a));
        User currentUser = userService.getById(userId);
        List<ShopCommentDTO> list = records.stream()
                .map(c -> {
                    Shop shop = shopMap.get(c.getShopId());
                    return buildDTO(c, currentUser, shop != null ? shop.getName() : null);
                })
                .collect(Collectors.toList());
        return Result.ok(list, page.getTotal());
    }

    @Override
    public Result summary(Long shopId) {
        if (shopId == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "店铺ID不能为空");
        }
        ShopCommentSummaryDTO dto = new ShopCommentSummaryDTO();
        dto.setCount(shopCommentMapper.countByShop(shopId));
        dto.setAvgRating(shopCommentMapper.avgRatingByShop(shopId));
        return Result.ok(dto);
    }

    @Override
    public Result delete(Long commentId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        ShopComment comment = getById(commentId);
        // 不存在或非本人评价，统一返回「评价不存在」，不泄露存在性
        if (comment == null || !userId.equals(comment.getUserId())) {
            return Result.fail(ErrorCode.SHOP_COMMENT_NOT_FOUND);
        }
        removeById(commentId);
        return Result.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateComment(Long commentId, ShopCommentUpdateRequest request) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        ShopComment comment = getById(commentId);
        // 不存在或非本人评价，统一返回「评价不存在」，不泄露存在性
        if (comment == null || !userId.equals(comment.getUserId())) {
            return Result.fail(ErrorCode.SHOP_COMMENT_NOT_FOUND);
        }
        ShopComment update = new ShopComment();
        update.setId(commentId);
        update.setContent(sanitize(request.getContent()));
        update.setRating(request.getRating());
        updateById(update);
        return Result.ok();
    }

    private Map<Long, User> loadUsers(List<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptyMap();
        }
        return userService.listByIds(ids.stream().distinct().collect(Collectors.toList())).stream()
                .collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
    }

    private ShopCommentDTO buildDTO(ShopComment comment, User user, String shopName) {
        ShopCommentDTO dto = new ShopCommentDTO();
        dto.setId(comment.getId());
        dto.setShopId(comment.getShopId());
        dto.setShopName(shopName);
        dto.setUserId(comment.getUserId());
        if (user != null) {
            dto.setNickName(user.getNickName());
            dto.setIcon(user.getIcon());
        }
        dto.setContent(comment.getContent());
        dto.setRating(comment.getRating());
        dto.setCreateTime(comment.getCreateTime());
        return dto;
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
