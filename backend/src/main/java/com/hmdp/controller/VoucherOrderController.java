package com.hmdp.controller;

import com.hmdp.dto.CreateVoucherOrderResultDTO;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.MerchantVoucherOrderItemDTO;
import com.hmdp.dto.MyVoucherItemDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderDetailDTO;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.security.MerchantAccessException;
import com.hmdp.security.MerchantShopGuard;
import com.hmdp.service.IMerchantVoucherOrderQueryService;
import com.hmdp.service.IMerchantVoucherRedeemService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 优惠券订单控制器
 * <p>
 * 提供优惠券下单相关的 REST API 接口，
 * 支持秒杀抢购、直接购买优惠券，
 * 以及订单状态查询、支付、取消、退款、删除等订单生命周期管理。
 * </p>
 *
 * <h3>商家接口的权限链（Phase 3.4-A 核销 / Phase 3.4-B 查询）</h3>
 * <pre>
 * RefreshTokenInterceptor -> 还原登录态
 * LoginInterceptor        -> /voucher-orders/** 未被放行，故要求登录
 * WriteAuthInterceptor    -> 只覆盖 /shops/**、/vouchers/**，<b>不覆盖 /voucher-orders/**</b>
 * MerchantShopGuard       -> 因此本接口自行 assertMerchant()（角色）+ assertOwner()（归属）
 * </pre>
 * 即：登录由拦截器保证，<b>角色与店铺归属必须由本 Controller 显式校验</b>。
 * 商家订单查询（{@code GET /merchant}）只需角色校验（查询范围由登录态推导，
 * 不接受客户端归属参数），故仅调用 {@code assertMerchant()}。
 */
@Validated
@RestController
@RequestMapping("/voucher-orders")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private MerchantShopGuard merchantShopGuard;

    @Resource
    private IMerchantVoucherRedeemService merchantVoucherRedeemService;

    @Resource
    private IMerchantVoucherOrderQueryService merchantVoucherOrderQueryService;

    /**
     * 秒杀下单（异步模式）
     *
     * @param voucherId 秒杀优惠券 ID
     * @return 订单 ID 或错误信息
     */
    @PostMapping("/seckill-orders")
    public Result<CreateVoucherOrderResultDTO> seckillVoucher(@RequestParam("voucherId") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 直接购买优惠券（同步模式）
     *
     * @param voucherId 优惠券 ID
     * @return 订单 ID 或错误信息
     */
    @PostMapping("/purchase-orders")
    public Result<CreateVoucherOrderResultDTO> purchaseVoucher(@RequestParam("voucherId") Long voucherId) {
        return voucherOrderService.purchaseVoucher(voucherId);
    }

    /**
     * 根据订单 ID 查询订单状态
     *
     * @param orderId 订单 ID
     * @return 订单状态或错误信息
     */
    @GetMapping("/{orderId}/status")
    public Result<Integer> getOrderStatus(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.getOrderStatus(orderId);
    }

    /**
     * 查询当前用户“我的优惠券”
     *
     * @param tab     标签：unused|used|expired
     * @param current 页码
     * @param size    每页数量
     * @return 列表结果
     */
    @GetMapping("/mine")
    public Result<List<MyVoucherItemDTO>> queryMyVouchers(@RequestParam(value = "tab", defaultValue = "unused") String tab,
                                                          @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
                                                          @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        return voucherOrderService.queryMyVouchers(tab, current, size);
    }

    /**
     * 查询订单详情（仅当前登录用户）
     *
     * @param orderId 订单 ID
     * @return 详情结果
     */
    @GetMapping("/{orderId}")
    public Result<VoucherOrderDetailDTO> queryOrderDetail(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.queryOrderDetail(orderId);
    }

    /**
     * 伪支付：模拟余额/微信/支付宝支付待支付订单
     *
     * @param orderId 订单 ID
     * @param payType 支付方式：1余额；2微信；3支付宝
     * @return 支付结果
     */
    @PostMapping("/{orderId}/pay")
    public Result<Void> payVoucherOrder(@PathVariable("orderId") Long orderId,
                                        @RequestParam(value = "payType", defaultValue = "1") Integer payType) {
        return voucherOrderService.payVoucherOrder(orderId, payType);
    }

    /**
     * 取消待支付订单
     *
     * @param orderId 订单 ID
     * @return 取消结果
     */
    @PostMapping("/{orderId}/cancel")
    public Result<Void> cancelVoucherOrder(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.cancelVoucherOrder(orderId);
    }

    /**
     * 退款：已支付未核销订单退款并恢复库存
     *
     * @param orderId 订单 ID
     * @return 退款结果
     */
    @PostMapping("/{orderId}/refund")
    public Result<Void> refundVoucherOrder(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.refundVoucherOrder(orderId);
    }

    /**
     * 删除已取消/已退款订单记录
     *
     * @param orderId 订单 ID
     * @return 删除结果
     */
    @DeleteMapping("/{orderId}")
    public Result<Void> deleteVoucherOrder(@PathVariable("orderId") Long orderId) {
        return voucherOrderService.deleteVoucherOrder(orderId);
    }

    /**
     * 商家核销订单（Phase 3.4-A）
     * <p>
     * 将一张「已支付（PAID=2）」的订单置为「已使用（USED=3）」，并写入核销时间。
     * 状态流转由 Service 层的单条条件 UPDATE（CAS）保证原子性，
     * 并发/重复核销只会成功一次，其余返回 {@code 30009}。
     * </p>
     *
     * <h4>权限链（本接口不在 WriteAuthInterceptor 覆盖范围内，需自行校验）</h4>
     * <ol>
     *   <li>匿名 → 401 + 10001；非商家（role≠1）→ 403 + 40003（{@code assertMerchant}）；</li>
     *   <li>订单不存在 → 200 + 30008；</li>
     *   <li>订单对应优惠券不存在 → 200 + 30000；</li>
     *   <li>券无归属（{@code shopId} 为空）→ 403 + 40004；</li>
     *   <li>券所属店铺非当前商家 → 403 + 40004（{@code assertOwner}，店铺不存在为 200 + 40001）；</li>
     *   <li>订单状态不是「已支付」→ 200 + 30009。</li>
     * </ol>
     * <p>
     * <b>越权口径</b>：先做角色校验、再做归属校验、最后才触碰状态，
     * 保证「非本店商家」无法通过核销接口探测或改写任何订单。
     * </p>
     *
     * @param orderId 订单 id
     * @return 成功返回 ok；状态不允许返回 30009
     */
    @PostMapping("/{orderId}/redeem")
    public Result<Void> redeemVoucherOrder(@PathVariable("orderId") Long orderId) {
        // 1) 商家身份校验：匿名 401 + 10001 / 非商家 403 + 40003
        //    （/voucher-orders/** 不在 WriteAuthInterceptor 覆盖内，必须在此显式校验角色）
        merchantShopGuard.assertMerchant();
        // 2) 订单必须存在（与「无权限」区分）
        VoucherOrder order = voucherOrderService.getById(orderId);
        if (order == null) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        // 3) 订单对应的优惠券必须存在
        Voucher voucher = voucherService.getById(order.getVoucherId());
        if (voucher == null) {
            return Result.fail(ErrorCode.VOUCHER_NOT_FOUND);
        }
        // 4) 无归属（历史/异常数据）的券：视为无人拥有，一律拒绝
        if (voucher.getShopId() == null) {
            throw new MerchantAccessException(ErrorCode.SHOP_NOT_OWNED, HttpStatus.FORBIDDEN);
        }
        // 5) 复用既有店铺归属校验：店铺不存在 200 + 40001；非本店 owner 403 + 40004
        merchantShopGuard.assertOwner(voucher.getShopId());
        // 6) 原子状态流转 PAID(2) -> USED(3)
        return merchantVoucherRedeemService.redeem(orderId);
    }

    /**
     * 商家分页查询「本店订单」（Phase 3.4-B）
     * <p>
     * 补上 Phase 3.4-A 核销能力的最后一块前置：此前商家只能凭已知 {@code orderId} 核销，
     * 本接口让商家先「找单」，再把 {@code orderId} 交给核销接口。
     * </p>
     *
     * <h4>权限链（本接口不在 WriteAuthInterceptor 覆盖范围内，需自行校验）</h4>
     * <ol>
     *   <li>匿名 → 401 + 10001（{@code LoginInterceptor}）；</li>
     *   <li>非商家（{@code role != 1}）→ 403 + 40003（{@code assertMerchant}）；</li>
     *   <li>商家（{@code role == 1}）→ 仅返回自己名下店铺的订单。</li>
     * </ol>
     *
     * <h4>查询范围（不可由客户端指定）</h4>
     * <p>
     * {@code merchantId} 由 {@code assertMerchant()} 从登录态取得，
     * 查询范围在 Service 层沿
     * {@code tb_shop.owner_id → tb_voucher.shop_id → tb_voucher_order.voucher_id}
     * 推导。本接口<b>不接受</b>客户端传入的 {@code shopId} / {@code voucherId} / {@code ownerId}，
     * 无店铺的商家返回空列表，<b>不会</b>退化为查询全部订单。
     * </p>
     *
     * <h4>路径优先级</h4>
     * <p>
     * {@code /merchant} 是字面量段，Spring {@code PathPatternParser} 下优先于
     * {@code /{orderId}}（与既有 {@code /mine} 同理），故不构成路由冲突。
     * </p>
     *
     * @param current 页码，默认 1（全局分页上限 100 由 MyBatis-Plus 处理，本接口不自行 clamp）
     * @param size    每页条数，默认 10
     * @param status  可选订单状态过滤：{@code null}（缺省）表示不过滤；
     *                取值 1 待支付 / 2 已支付 / 3 已核销 / 4 已取消 / 5 已过期 / 6 已退款
     * @return 分页结果：{@code data} 为订单列表项，{@code total} 为该商家订单总数
     */
    @GetMapping("/merchant")
    public Result<List<MerchantVoucherOrderItemDTO>> queryMerchantOrders(
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size,
            @RequestParam(value = "status", required = false) Integer status) {
        // 商家身份校验：匿名 401 + 10001 / 非商家 403 + 40003
        // （/voucher-orders/** 不在 WriteAuthInterceptor 覆盖内，必须在此显式校验角色）
        Long merchantId = merchantShopGuard.assertMerchant();
        return merchantVoucherOrderQueryService.queryMerchantOrders(merchantId, current, size, status);
    }
}