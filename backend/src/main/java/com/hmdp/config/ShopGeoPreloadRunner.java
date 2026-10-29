package com.hmdp.config;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 商铺 GEO 数据预热
 * <p>
 * 应用启动后将全部商铺按类型写入 Redis GEO（shop:geo:{typeId}），
 * member 为店铺 id，坐标取店铺经纬度，供首页/地图页按距离排序查询。
 * </p>
 */
@Slf4j
@Component
public class ShopGeoPreloadRunner implements ApplicationRunner {

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        List<Shop> shops = shopService.list();
        if (shops == null || shops.isEmpty()) {
            log.warn("商铺 GEO 预热跳过：无商铺数据");
            return;
        }
        Map<String, List<Shop>> byTypeAndCity = shops.stream()
            .collect(Collectors.groupingBy(shop -> shop.getTypeId() + ":" + shop.getCity()));
        int total = 0;
        for (Map.Entry<String, List<Shop>> entry : byTypeAndCity.entrySet()) {
            Map<String, Point> members = entry.getValue().stream()
                .collect(Collectors.toMap(
                    shop -> shop.getId().toString(),
                    shop -> new Point(shop.getX(), shop.getY())
                ));
            stringRedisTemplate.opsForGeo().add(SHOP_GEO_KEY + entry.getKey(), members);
            total += members.size();
        }
        long cityCount = byTypeAndCity.keySet().stream()
            .map(k -> k.substring(k.indexOf(':') + 1))
            .distinct()
            .count();
        log.info("商铺 GEO 预热完成：{} 个类型维度（{} 个城市），共 {} 家商铺", byTypeAndCity.size(), cityCount, total);
    }
}