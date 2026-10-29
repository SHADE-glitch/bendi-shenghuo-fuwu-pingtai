package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.BrowseHistoryCreateRequest;
import com.hmdp.dto.BrowseHistoryDTO;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.BrowseHistory;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.BrowseHistoryMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IBrowseHistoryService;
import com.hmdp.service.IShopService;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 浏览历史服务实现类
 * <p>
 * 浏览历史由前端在打开店铺 / 笔记详情页时上报，去重写入 tb_browse_history；
 * 读取时批量回填目标标题与首图。
 * </p>
 */
@Service
public class BrowseHistoryServiceImpl extends ServiceImpl<BrowseHistoryMapper, BrowseHistory> implements IBrowseHistoryService {

    private static final int TARGET_SHOP = 1;
    private static final int TARGET_BLOG = 2;

    @Resource
    private BrowseHistoryMapper browseHistoryMapper;

    @Resource
    private IShopService shopService;

    @Resource
    private IBlogService blogService;

    @Override
    public Result record(BrowseHistoryCreateRequest request) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Integer targetType = request.getTargetType();
        if (targetType == null || (targetType != TARGET_SHOP && targetType != TARGET_BLOG)) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "无效的目标类型");
        }
        Long targetId = request.getTargetId();
        if (targetId == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "目标ID不能为空");
        }
        // 目标必须存在，避免记录脏数据
        if (targetType == TARGET_SHOP) {
            if (shopService.getById(targetId) == null) {
                return Result.fail(ErrorCode.SHOP_NOT_FOUND);
            }
        } else {
            if (blogService.getById(targetId) == null) {
                return Result.fail(ErrorCode.BLOG_NOT_FOUND);
            }
        }
        browseHistoryMapper.upsertHistory(userId, targetType, targetId);
        return Result.ok();
    }

    @Override
    public Result queryMine(Integer current, Integer size) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Page<BrowseHistory> page = query()
                .eq("user_id", userId)
                .orderByDesc("update_time")
                .page(new Page<>(current, size));
        List<BrowseHistory> records = page.getRecords();
        if (CollectionUtils.isEmpty(records)) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }

        List<Long> shopIds = records.stream()
                .filter(h -> h.getTargetType() != null && h.getTargetType() == TARGET_SHOP)
                .map(BrowseHistory::getTargetId)
                .distinct()
                .collect(Collectors.toList());
        List<Long> blogIds = records.stream()
                .filter(h -> h.getTargetType() != null && h.getTargetType() == TARGET_BLOG)
                .map(BrowseHistory::getTargetId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Shop> shopMap = shopIds.isEmpty()
                ? Collections.emptyMap()
                : shopService.listByIds(shopIds).stream()
                    .collect(Collectors.toMap(Shop::getId, Function.identity(), (a, b) -> a));
        Map<Long, Blog> blogMap = blogIds.isEmpty()
                ? Collections.emptyMap()
                : blogService.listByIds(blogIds).stream()
                    .collect(Collectors.toMap(Blog::getId, Function.identity(), (a, b) -> a));

        List<BrowseHistoryDTO> list = records.stream()
                .map(h -> buildDTO(h, shopMap, blogMap))
                .collect(Collectors.toList());
        return Result.ok(list, page.getTotal());
    }

    @Override
    public Result clear() {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        remove(Wrappers.<BrowseHistory>lambdaQuery().eq(BrowseHistory::getUserId, userId));
        return Result.ok();
    }

    private BrowseHistoryDTO buildDTO(BrowseHistory history, Map<Long, Shop> shopMap, Map<Long, Blog> blogMap) {
        BrowseHistoryDTO dto = new BrowseHistoryDTO();
        dto.setId(history.getId());
        dto.setTargetType(history.getTargetType());
        dto.setTargetId(history.getTargetId());
        dto.setCreateTime(history.getUpdateTime());
        if (history.getTargetType() != null && history.getTargetType() == TARGET_SHOP) {
            Shop shop = shopMap.get(history.getTargetId());
            if (shop != null) {
                dto.setTitle(shop.getName());
                dto.setImage(firstImage(shop.getImages()));
            }
        } else if (history.getTargetType() != null && history.getTargetType() == TARGET_BLOG) {
            Blog blog = blogMap.get(history.getTargetId());
            if (blog != null) {
                dto.setTitle(blog.getTitle());
                dto.setImage(firstImage(blog.getImages()));
            }
        }
        return dto;
    }

    /**
     * 从 JSON 数组字符串中取首图；兼容普通逗号分隔的旧数据。
     */
    private String firstImage(String images) {
        if (images == null || images.isEmpty()) {
            return null;
        }
        try {
            List<String> list = JSONUtil.toList(images, String.class);
            if (CollectionUtils.isNotEmpty(list)) {
                return list.get(0);
            }
        } catch (Exception ignored) {
            // 非 JSON，按逗号分隔兜底
            String[] parts = images.split(",");
            if (parts.length > 0 && !parts[0].isBlank()) {
                return parts[0].trim();
            }
        }
        return null;
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }
}
