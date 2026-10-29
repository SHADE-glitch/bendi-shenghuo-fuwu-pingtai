package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 商铺服务接口
 * <p>提供商铺信息查询、更新及基于 GEO 的分页查询能力。</p>
 */
public interface IShopService extends IService<Shop> {

    /**
     * 商家创建店铺
     * <p>
     * 归属商家（{@code owner_id}）由服务端从当前登录态
     * （{@link com.hmdp.utils.UserHolder}）注入，<b>忽略</b>调用方传入的
     * {@code ownerId}，即前端无法决定店铺归属。
     * </p>
     *
     * @param shop 商铺信息实体（其 ownerId 会被服务端覆盖）
     * @return 包含新商铺 ID 的结果集
     */
    Result<Long> createShop(Shop shop);

    /**
     * 根据商铺 ID 查询商铺详情
     *
     * @param id 商铺 ID
     * @return 查询结果
     */
    Result queryById(Long id);

    /**
     * 更新商铺信息（更新后删除缓存，保证缓存一致性）
     *
     * @param shop 商铺信息实体
     * @return 更新结果
     */
    Result updateShop(Shop shop);

    /**
     * 根据商铺类型分页查询商铺列表（支持 GEO 坐标距离排序，按城市隔离）
     *
     * @param typeId  商铺类型
     * @param current 当前页码
     * @param x       经度（可选，用于距离排序）
     * @param y       纬度（可选，用于距离排序）
     * @param city    城市名（可选，按城市过滤）
     * @return 分页查询结果
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y, String city);

    /**
     * 根据商铺名称关键字分页查询商铺列表
     *
     * @param name    商铺名称模糊匹配关键字
     * @param current 当前页码
     * @return 分页查询结果
     */
    Result queryShopByName(String name, Integer current);

    /**
     * 查询指定商家拥有的店铺列表（Merchant 「我的店铺」）
     * <p>
     * 查询条件为 {@code tb_shop.owner_id = ownerId}。{@code ownerId} 必须由
     * Controller 从<b>当前登录态</b>取得（见 {@code MerchantShopGuard.assertMerchant()}），
     * <b>不得</b>直接使用客户端传入的参数。
     * </p>
     * <p>
     * 商家店铺数量级很小（无唯一约束但单商家店铺有限），故不做分页。
     * </p>
     *
     * @param ownerId 商家用户 id（当前登录商家）
     * @return 该商家拥有的店铺列表（按创建先后倒序）
     */
    Result<List<Shop>> queryShopsByOwner(Long ownerId);
}
