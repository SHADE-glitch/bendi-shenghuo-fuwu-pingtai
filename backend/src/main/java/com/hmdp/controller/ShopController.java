package com.hmdp.controller;

import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopSaveRequest;
import com.hmdp.dto.ShopUpdateRequest;
import com.hmdp.entity.Shop;
import com.hmdp.security.MerchantShopGuard;
import com.hmdp.service.IShopService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;

/**
 * 店铺控制器
 * <p>
 * 提供店铺的详情查询、新增、更新、按类型分页查询（支持 GEO 坐标排序）、
 * 按名称关键字分页查询等 REST API 接口。
 * </p>
 */
@Validated
@RestController
@RequestMapping("/shops")
public class ShopController {

    @Resource
    public IShopService shopService;

    @Resource
    private MerchantShopGuard merchantShopGuard;

    /**
     * 根据商铺 ID 查询商铺详情
     *
     * @param shopId 商铺 ID
     * @return 包含商铺详情数据的结果集
     */
    @GetMapping("/{shopId}")
    public Result<Shop> queryShopById(@PathVariable("shopId") Long shopId) {
        return shopService.queryById(shopId);
    }

    /**
     * 新增商铺信息
     * <p>
     * 归属商家（{@code owner_id}）由 Service 层从当前登录态注入，
     * 请求体中的 {@code ownerId} 会被覆盖，前端无法决定店铺归属
     * （{@link ShopSaveRequest} 本身也不声明该字段）。
     * </p>
     * <p>
     * 请求体绑定 {@link ShopSaveRequest} 而非 {@link Shop} 实体：{@code tb_shop} 有 9 个
     * {@code NOT NULL} 且无 DEFAULT 的列，实体零校验注解会让 {@code @Valid} 形同虚设，
     * 缺列时在数据库层才炸并返回 99999。详见 {@link ShopSaveRequest} 类注释。
     * </p>
     *
     * @param request 商铺信息
     * @return 包含新商铺 ID 的结果集
     */
    @PostMapping
    public Result<Long> saveShop(@Valid @RequestBody ShopSaveRequest request) {
        return shopService.createShop(toShop(request));
    }

    /**
     * 更新商铺信息
     * <p>
     * 权限分两层：{@code WriteAuthInterceptor} 保证调用方是商家（role=1），
     * 本方法先经 {@link MerchantShopGuard#assertOwner(Long)} 保证调用方是该店铺的 owner，
     * 校验通过后才进入 {@code updateShop}（越权请求不会触达更新逻辑）。
     * </p>
     * <p>
     * 请求体绑定 {@link ShopUpdateRequest}（全字段可缺省，保持部分更新语义）。
     * 由于 MyBatis-Plus 的 {@code NOT_NULL} 更新策略会剔除 {@code null} 字段，
     * 空请求体会生成无 SET 子句的非法 UPDATE，因此此处显式要求
     * <b>至少提交一个待更新字段</b>。
     * </p>
     *
     * @param shopId 商铺 ID
     * @param request 商铺信息
     * @return 成功返回 ok（{@code data} 为空）；校验失败时 {@code errorCode=10008}、
     *         {@code data} 给出可读原因
     */
    @PutMapping("/{shopId}")
    public Result<String> updateShop(@PathVariable("shopId") Long shopId,
                                     @Valid @RequestBody ShopUpdateRequest request) {
        // owner 归属校验：非该店铺归属商家 -> 403 + 40004；店铺不存在 -> 40001
        merchantShopGuard.assertOwner(shopId);
        if (!request.hasAnyField()) {
            return Result.fail(ErrorCode.PARAM_INVALID, "至少提交一个待更新的店铺字段");
        }
        Shop shop = new Shop();
        applyTo(request, shop);
        shop.setId(shopId);
        return shopService.updateShop(shop);
    }

    /**
     * 新增请求 DTO → 实体（只写入 DTO 声明的白名单字段）
     * <p>
     * 映射放在 Controller 而非 Service：{@code IShopService.createShop(Shop)} 的签名保持不变，
     * 避免为一次入参校验改动接口契约。
     * </p>
     *
     * @param request 新增请求参数
     * @return 待插入的 {@link Shop}（{@code id}/{@code ownerId}/时间戳由服务端或数据库负责）
     */
    private Shop toShop(ShopSaveRequest request) {
        Shop shop = new Shop();
        applyTo(request.getName(), request.getCity(), request.getTypeId(), request.getImages(),
                request.getArea(), request.getAddress(), request.getX(), request.getY(),
                request.getAvgPrice(), request.getSold(), request.getComments(),
                request.getScore(), request.getOpenHours(), shop);
        return shop;
    }

    /**
     * 修改请求 DTO → 实体（只写入非 {@code null} 的白名单字段）
     *
     * @param request 修改请求参数
     * @param shop    待回填的实体（保持未被请求覆盖的字段为 {@code null}，
     *                由 {@code updateById} 的 {@code NOT_NULL} 策略跳过）
     */
    private void applyTo(ShopUpdateRequest request, Shop shop) {
        applyTo(request.getName(), request.getCity(), request.getTypeId(), request.getImages(),
                request.getArea(), request.getAddress(), request.getX(), request.getY(),
                request.getAvgPrice(), request.getSold(), request.getComments(),
                request.getScore(), request.getOpenHours(), shop);
    }

    /**
     * 把 13 个业务字段逐个搬进实体。
     * <p>
     * 用无脑赋值而非逐字段判空：{@code null} 就是「本次不更新」的信号，
     * 由 MyBatis-Plus 的 {@code NOT_NULL} 策略在 SQL 层统一处理，
     * Java 层不再重复同一语义。
     * </p>
     */
    private void applyTo(String name, String city, Long typeId, String images, String area,
                         String address, Double x, Double y, Long avgPrice, Integer sold,
                         Integer comments, Integer score, String openHours, Shop shop) {
        shop.setName(name);
        shop.setCity(city);
        shop.setTypeId(typeId);
        shop.setImages(images);
        shop.setArea(area);
        shop.setAddress(address);
        shop.setX(x);
        shop.setY(y);
        shop.setAvgPrice(avgPrice);
        shop.setSold(sold);
        shop.setComments(comments);
        shop.setScore(score);
        shop.setOpenHours(openHours);
    }

    /**
     * 查询当前登录商家的「我的店铺」列表
     * <p>
     * 商家私有接口。查询条件取<b>当前登录态</b>（{@link MerchantShopGuard#assertMerchant()}），
     * <b>不接受</b>客户端传入的 ownerId，因此无法查询其他商家的店铺。
     * </p>
     * <p>
     * 权限：匿名 → 401 + 10001；普通用户（role≠1）→ 403 + 40003；
     * 商家 → 仅返回 {@code owner_id == 当前用户 id} 的店铺。
     * </p>
     *
     * @return 当前商家拥有的店铺列表
     */
    @GetMapping("/my")
    public Result<List<Shop>> queryMyShops() {
        // 商家身份校验：返回当前登录商家 id（匿名 401 / 非商家 403），随后按该 id 查询
        Long ownerId = merchantShopGuard.assertMerchant();
        return shopService.queryShopsByOwner(ownerId);
    }

    /**
     * 根据商铺类型分页查询商铺信息
     * <p>如果传入了坐标（x, y），则会按照距离远近进行排序</p>
     *
     * @param typeId  商铺类型 ID
     * @param current 当前页码，默认为 1
     * @param x       用户当前位置经度（可选）
     * @param y       用户当前位置纬度（可选）
     * @param city    城市名（可选）
     * @return 包含商铺列表的结果集
     */
    @GetMapping
    public Result<List<Shop>> queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y,
            @RequestParam(value = "city", required = false) String city) {
        return shopService.queryShopByType(typeId, current, x, y, city);
    }

    /**
     * 根据商铺名称关键字分页查询商铺列表
     *
     * @param name    商铺名称模糊匹配关键字
     * @param current 当前页码，默认为 1
     * @return 包含符合条件的商铺记录结果集
     */
    @GetMapping("/search")
    public Result<List<Shop>> queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current) {
        return shopService.queryShopByName(name, current);
    }
}