package com.hmdp.controller;

import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherSaveRequest;
import com.hmdp.dto.VoucherUpdateRequest;
import com.hmdp.entity.Voucher;
import com.hmdp.security.MerchantAccessException;
import com.hmdp.security.MerchantShopGuard;
import com.hmdp.service.IVoucherService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.List;

/**
 * 优惠券控制器
 * <p>
 * 提供优惠券管理相关的 REST API 接口，
 * 支持普通优惠券和秒杀优惠券的新增，按店铺查询优惠券列表，
 * 以及商家维度的「我的优惠券」查询与修改。
 * </p>
 *
 * <h3>权限链（写接口）</h3>
 * <pre>
 * RefreshTokenInterceptor  -> 还原登录态
 * LoginInterceptor         -> （/vouchers/** 整体放行，读接口匿名可用）
 * WriteAuthInterceptor     -> 「是不是商家？」写方法要求 role == 1
 * VoucherController        -> MerchantShopGuard「这家店是不是你的？」
 * IVoucherService          -> 业务层
 * </pre>
 * 归属校验在 Controller 层完成，<b>不改动 VoucherServiceImpl 的创建/库存/秒杀逻辑</b>。
 *
 * <h3>权限链（商家私有读接口）</h3>
 * <pre>
 * GET /vouchers/my  ->  WriteAuthInterceptor 只管写方法，GET 不在其内，
 *                      且 /vouchers/** 整体从 LoginInterceptor 放行，
 *                      故由 MerchantShopGuard.assertMerchant() 自行完成身份校验。
 * </pre>
 */
@RestController
@RequestMapping("/vouchers")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    @Resource
    private MerchantShopGuard merchantShopGuard;

    /**
     * 新增普通券
     * <p>
     * 仅允许商家为自己拥有的店铺（{@code shop.owner_id == 当前用户 id}）创建；
     * 非本店 owner 在进入业务层之前即被 {@link MerchantShopGuard} 拒绝，
     * 因此越权请求不会产生任何 {@code tb_voucher} 记录。
     * </p>
     *
     * @param request 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result<Long> addVoucher(@Valid @RequestBody VoucherSaveRequest request) {
        merchantShopGuard.assertOwner(request.getShopId());
        return voucherService.addVoucher(request);
    }

    /**
     * 新增秒杀券
     * <p>
     * 归属校验与普通券一致：先确认请求中的 {@code shopId} 属于当前商家，
     * 再进入业务层。校验失败时秒杀券、秒杀库存（DB）与 Redis 秒杀库存均不会写入。
     * </p>
     *
     * @param request 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("/seckill-vouchers")
    public Result<Long> addSeckillVoucher(@Valid @RequestBody VoucherSaveRequest request) {
        merchantShopGuard.assertOwner(request.getShopId());
        return voucherService.addSeckillVoucher(request);
    }

    /**
     * 查询店铺的优惠券列表
     *
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping
    public Result<List<Voucher>> queryVoucherOfShop(@RequestParam("shopId") Long shopId) {
        return voucherService.queryVoucherOfShop(shopId);
    }

    /**
     * 查询当前登录商家名下所有店铺的优惠券（Merchant「我的优惠券」）
     * <p>
     * 商家私有接口。查询范围由<b>当前登录态</b>推导：
     * {@code 当前商家 → 其名下所有 Shop → 这些 Shop 下的 Voucher}，
     * <b>不接受</b>客户端传入的 ownerId/shopId，因此无法查询其他商家的券。
     * </p>
     * <p>
     * 权限：匿名 → 401 + 10001；普通用户（role≠1）→ 403 + 40003。
     * 与 {@code GET /shops/my} 一致，均依赖 {@link MerchantShopGuard#assertMerchant()}。
     * </p>
     *
     * @return 当前商家名下所有店铺的优惠券列表
     */
    @GetMapping("/my")
    public Result<List<Voucher>> queryMyVouchers() {
        // 商家身份校验：返回当前登录商家 id（匿名 401 / 非商家 403），随后按该 id 聚合
        Long ownerId = merchantShopGuard.assertMerchant();
        return voucherService.queryVouchersByOwner(ownerId);
    }

    /**
     * 修改优惠券（Merchant 侧）
     * <p>
     * 归属校验沿用既有约定（与 {@code POST /vouchers} 一致）：
     * 由 {@code voucherId} 反查 {@code Voucher.shopId}，再复用
     * {@link MerchantShopGuard#assertOwner(Long)} 判断该店铺是否属于当前商家，
     * <b>不新增 Voucher 专属 Guard</b>。
     * </p>
     * <p>
     * 只允许修改白名单字段（见 {@link VoucherUpdateRequest}）：
     * 请求体无法改变券的归属（{@code shopId}）、类型（{@code type}）
     * 与秒杀状态（{@code stock}/{@code beginTime}/{@code endTime}）。
     * </p>
     * <p>
     * 权限：匿名 → 401 + 10001；普通用户 → 403 + 40003（均由 {@code WriteAuthInterceptor} 保证）；
     * 券不存在 → 200 + 30000；券不属于当前商家 → 403 + 40004。
     * </p>
     *
     * @param voucherId 优惠券 id
     * @param request   可修改字段
     * @return 成功返回 ok
     */
    @PutMapping("/{voucherId}")
    public Result<Void> updateVoucher(@PathVariable("voucherId") Long voucherId,
                                      @Valid @RequestBody VoucherUpdateRequest request) {
        // 1) voucherId -> Voucher：券不存在与「无权限」严格区分
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail(ErrorCode.VOUCHER_NOT_FOUND);
        }
        // 2) 无归属（历史/异常数据）的券：视为无人拥有，一律拒绝；
        //    同时避免把 null 传入 assertOwner 造成无意义的店铺查询
        if (voucher.getShopId() == null) {
            throw new MerchantAccessException(ErrorCode.SHOP_NOT_OWNED, HttpStatus.FORBIDDEN);
        }
        // 3) 复用既有店铺归属校验：店铺不存在 -> 40001；非本店 owner -> 403 + 40004
        merchantShopGuard.assertOwner(voucher.getShopId());
        // 4) 白名单字段更新
        return voucherService.updateOwnVoucher(voucherId, request);
    }
}