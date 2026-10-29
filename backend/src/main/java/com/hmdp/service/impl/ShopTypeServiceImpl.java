package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * 商铺类型服务实现类
 * <p>
 * 提供商铺类型列表查询功能，采用本地缓存 + Redis 双层缓存策略解决热点 Key 问题。
 * 查询顺序为：ConcurrentHashMap 本地缓存 -> Redis 缓存 -> 数据库，并自动回填缓存。
 * </p>
 */
@Slf4j
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 本地缓存，解决热点Key问题
     * 使用ConcurrentHashMap模拟本地缓存，生产环境可使用Caffeine
     */
    private static final Map<String, List<ShopType>> LOCAL_CACHE = new ConcurrentHashMap<>();
    private static final long LOCAL_CACHE_TTL = 5 * 60 * 1000; // 5分钟本地缓存
    private volatile long lastCacheTime = 0;

    @Override
    public Result queryTypeList() {
        // 1.先检查本地缓存
        List<ShopType> cachedTypes = getFromLocalCache();
        if (cachedTypes != null) {
            return Result.ok(cachedTypes);
        }
        
        // 2.检查Redis缓存
        String key = CACHE_SHOP_TYPE_KEY;
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(key, 0, -1);
        
        if (CollectionUtil.isNotEmpty(shopTypeJson)) {
            // JSON字符串转对象 排序后返回
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson.toString(), ShopType.class);
            Collections.sort(shopTypes, (o1, o2) -> o1.getSort() - o2.getSort());
            // 更新本地缓存
            updateLocalCache(shopTypes);
            return Result.ok(shopTypes);
        }
        
        // 3.没有缓存，查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        
        if (CollectionUtil.isEmpty(shopTypes)) {
            return Result.fail("商铺类型不存在...");
        }
        
        // 4.写入Redis
        List<String> shopTypesJson = shopTypes.stream()
                .map(shopType -> JSONUtil.toJsonStr(shopType))
                .collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypesJson);
        
        // 5.更新本地缓存
        updateLocalCache(shopTypes);
        
        // 6.返回
        return Result.ok(shopTypes);
    }

    /**
     * 从本地缓存获取
     */
    private List<ShopType> getFromLocalCache() {
        if (System.currentTimeMillis() - lastCacheTime < LOCAL_CACHE_TTL) {
            return LOCAL_CACHE.get("shopTypes");
        }
        return null;
    }

    /**
     * 更新本地缓存
     */
    private void updateLocalCache(List<ShopType> shopTypes) {
        LOCAL_CACHE.put("shopTypes", shopTypes);
        lastCacheTime = System.currentTimeMillis();
    }
}
