package com.hmdp.service.impl;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import java.util.*;
import java.util.concurrent.TimeUnit;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

/**
 * 商铺服务实现类
 * <p>
 * 提供商铺信息查询、更新及基于 GEO 的分页查询能力。
 * 支持按商铺类型和坐标距离进行分页检索，包含 Redis 缓存管理和距离计算功能。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl
    extends ServiceImpl<ShopMapper, Shop>
    implements IShopService
{

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 商家创建店铺
     * <p>
     * 归属商家（{@code owner_id}）由服务端从当前登录态注入：
     * 取 {@link UserHolder} 中当前商家的 id 覆盖 {@code shop.ownerId}，
     * 因此请求体/前端即使携带 {@code ownerId} 也会被忽略，无法伪造归属。
     * </p>
     * <p>
     * 注：{@code POST /shops} 已由 {@code WriteAuthInterceptor} 保证调用方为
     * 已登录商家（role=1），此处的空值分支仅为兜底，避免产生无归属店铺。
     * </p>
     *
     * @param shop 商铺信息实体（其 ownerId 会被服务端覆盖）
     * @return 包含新商铺 ID 的结果集
     */
    @Override
    public Result<Long> createShop(Shop shop) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        // 归属由服务端决定：覆盖请求体中可能传入/伪造的 ownerId
        shop.setOwnerId(user.getId());
        save(shop);
        // 新店必须立刻进入 GEO，否则地图/附近列表要等到下次重启预热才可见
        syncShopGeo(shop);
        return Result.ok(shop.getId());
    }

    /**
     * 根据id查询商铺数据
     *
     * @param id 商户ID
     * @return 商户信息
     */
    @Override
    public Result queryById(Long id) {
        // 使用 CacheClient 同时处理穿透、击穿，并通过随机 TTL 降低雪崩风险
        Shop shop = cacheClient.handleCacheBreakdownWithMutex(
            CACHE_SHOP_KEY,
            LOCK_SHOP_KEY,
            id,
            Shop.class,
            this::getById,
            CACHE_SHOP_TTL,
            TimeUnit.MINUTES
        );

        if (shop == null) {
            // 业务「未找到」必须是 40001：Result.fail(String) 会把 errorCode 抹成 99999 系统错误。
            // 与 MerchantShopGuard.assertOwner / ShopComment 既有约定一致（同为 40001 + 同文案）。
            return Result.fail(ErrorCode.SHOP_NOT_FOUND);
        }

        return Result.ok(shop);
    }

    @Override
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "店铺id can't be null");
        }
        Shop before = getById(id);
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        // 改类型/城市/坐标后 GEO 必须跟着走：旧 key 里的成员要摘掉，新 key 里重新写入，
        // 否则地图按距离检索会一直返回旧位置（或把店铺留在已不存在的类型下）。
        Shop after = getById(id);
        if (after != null) {
            if (before != null && !geoKey(before).equals(geoKey(after))) {
                removeShopGeo(before);
            }
            syncShopGeo(after);
        }
        return Result.ok();
    }

    /** 店铺所属的 GEO key：与预热脚本、查询侧保持同一拼法。 */
    private String geoKey(Shop shop) {
        String citySuffix = StrUtil.isBlank(shop.getCity()) ? "" : ":" + shop.getCity();
        return SHOP_GEO_KEY + shop.getTypeId() + citySuffix;
    }

    /**
     * 把店铺坐标写入 GEO。GEO 只是检索索引，写失败不应让店铺创建/更新失败。
     */
    private void syncShopGeo(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getTypeId() == null
                || shop.getX() == null || shop.getY() == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForGeo()
                    .add(geoKey(shop), new Point(shop.getX(), shop.getY()), shop.getId().toString());
        } catch (Exception e) {
            log.warn("同步店铺 GEO 失败，等待下次预热修复, shopId={}", shop.getId(), e);
        }
    }

    private void removeShopGeo(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getTypeId() == null) {
            return;
        }
        try {
            stringRedisTemplate.opsForGeo().remove(geoKey(shop), shop.getId().toString());
        } catch (Exception e) {
            log.warn("清理旧店铺 GEO 失败, shopId={}", shop.getId(), e);
        }
    }

    /**
     * 查询指定商家拥有的店铺列表（Merchant「我的店铺」）
     * <p>
     * 直接按 {@code owner_id} 走库（命中 {@code idx_shop_owner} 索引），
     * 不经过店铺缓存，避免缓存值与归属列表不一致。
     * {@code ownerId} 由 Controller 从当前登录态注入，不接受客户端参数。
     * </p>
     */
    @Override
    public Result<List<Shop>> queryShopsByOwner(Long ownerId) {
        // 防御性：归属查询条件为空时不得退化为「查询全部店铺」
        if (ownerId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        List<Shop> shops = query()
                .eq("owner_id", ownerId)
                .orderByDesc("id")
                .list();
        return Result.ok(shops);
    }

    /**
     * 根据商铺类型分页查询商铺信息（支持GEO坐标排序，无GEO数据或超出距离时降级回源数据库）
     */
    @Override
    public Result queryShopByType(
        Integer typeId,
        Integer current,
        Double x,
        Double y,
        String city
    ) {
        String citySuffix = StrUtil.isBlank(city) ? "" : ":" + city;
        // 0.坐标范围校验（经度 [-180,180]，纬度 [-90,90]）
        if (x != null && (x < -180 || x > 180)) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "经度范围不合法，应在[-180,180]之间");
        }
        if (y != null && (y < -90 || y > 90)) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "纬度范围不合法，应在[-90,90]之间");
        }
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                .eq("type_id", typeId)
                .eq(StrUtil.isNotBlank(city), "city", city)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 带出 total，前端据此判断能否继续翻页（GEO 分支不返回 total，由调用方按页长收敛）
            return Result.ok(page.getRecords(), page.getTotal());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId + citySuffix;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = null;
        try {
            results = stringRedisTemplate
                .opsForGeo()
                .search(
                    key,
                    GeoReference.fromCoordinate(x, y),
                    new Distance(50000), // 扩大距离半径至 50km
                    RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeDistance()
                        .limit(end)
                );
        } catch (Exception e) {
            log.warn("查询 Redis GEO 异常，降级按数据库查询, typeId={}", typeId, e);
        }

        // 4.判断 Redis GEO 是否命中数据
        if (results == null || results.getContent() == null || results.getContent().isEmpty()) {
            // GEO 未命中/未预热或超出搜寻距离，降级按数据库查询，确保前端能正常展示商户
            return Result.ok(queryShopsByTypeWithDistance(typeId, current, x, y, city));
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }

        // 4.1.截取 from ~ end 的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list
            .stream()
            .skip(from)
            .forEach(result -> {
                // 4.2.获取店铺id
                String shopIdStr = result.getContent().getName();
                ids.add(Long.valueOf(shopIdStr));
                // 4.3.获取距离
                Distance distance = result.getDistance();
                distanceMap.put(shopIdStr, distance);
            });

        if (ids.isEmpty()) {
            return Result.ok(queryShopsByTypeWithDistance(typeId, current, x, y, city));
        }

        // 5.根据id查询Shop
        List<Shop> shops = query().in("id", ids).list();
        Map<Long, Shop> shopMap = new HashMap<>();
        for (Shop shop : shops) {
            shopMap.put(shop.getId(), shop);
        }
        List<Shop> sortedShops = new ArrayList<>();
        for (Long shopId : ids) {
            Shop shop = shopMap.get(shopId);
            if (shop != null) {
                sortedShops.add(shop);
            }
        }

        for (Shop shop : sortedShops) {
            if (distanceMap.containsKey(shop.getId().toString())) {
                shop.setDistance(
                    distanceMap.get(shop.getId().toString()).getValue()
                );
            }
        }
        // 6.返回
        return Result.ok(sortedShops);
    }

    /**
     * 根据商铺名称关键字分页查询商铺列表
     *
     * @param name    商铺名称模糊匹配关键字
     * @param current 当前页码
     * @return 分页查询结果
     */
    @Override
    public Result queryShopByName(String name, Integer current) {
        // 分页；页大小与分类浏览保持一致（此前用 MAX_PAGE_SIZE=10，同一列表两套尺寸）
        Page<Shop> page = query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        // 带出 total：此前只回 list，前端无法判断是否还有下一页
        return Result.ok(page.getRecords(), page.getTotal());
    }

    /**
     * GEO 降级兜底：查询所有匹配商铺，计算距离并按距离升序排序后内存分页。
     */
    private List<Shop> queryShopsByTypeWithDistance(Integer typeId, Integer current, Double x, Double y, String city) {
        List<Shop> all = query()
                .eq("type_id", typeId)
                .eq(StrUtil.isNotBlank(city), "city", city)
                .list();
        fillFallbackDistance(all, x, y);
        all.sort((a, b) -> {
            Double da = a.getDistance();
            Double db = b.getDistance();
            if (da == null && db == null) return 0;
            if (da == null) return 1;
            if (db == null) return -1;
            return Double.compare(da, db);
        });
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int to = Math.min(from + SystemConstants.DEFAULT_PAGE_SIZE, all.size());
        return from >= all.size() ? Collections.emptyList() : all.subList(from, to);
    }

    /**
     * 降级兜底：GEO 未命中（坐标超范围/未预热）时，用 Haversine 公式
     * 计算各商铺到目标坐标的距离（单位：米），确保前端始终能展示距离。
     */
    private void fillFallbackDistance(List<Shop> shops, Double x, Double y) {
        if (shops == null || x == null || y == null) {
            return;
        }
        for (Shop shop : shops) {
            if (shop.getX() == null || shop.getY() == null) {
                continue;
            }
            shop.setDistance(haversineMeters(y, x, shop.getY(), shop.getX()));
        }
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }
}
