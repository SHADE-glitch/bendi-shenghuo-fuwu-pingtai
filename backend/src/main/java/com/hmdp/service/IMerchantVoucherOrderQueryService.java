package com.hmdp.service;

import com.hmdp.dto.MerchantVoucherOrderItemDTO;
import com.hmdp.dto.Result;

import java.util.List;

/**
 * 商家订单查询服务接口（Phase 3.4-B）
 * <p>
 * <b>职责</b>：让商家分页查看<b>自己名下店铺</b>产生的优惠券订单，用于「找单」——
 * 补上 Phase 3.4-A 核销能力的最后一块前置（此前商家只能凭已知 {@code orderId} 核销）。
 * </p>
 *
 * <h3>为什么是独立接口（而不是扩展 IVoucherOrderService）</h3>
 * <p>
 * 既有 {@code IVoucherOrderService} 的实现类 {@code VoucherOrderServiceImpl}
 * 承载秒杀 / Redis Stream / Pending List / Lua 等核心链路，已被冻结；
 * 其 {@code queryMyVouchers} 是<b>消费者维度</b>（按 {@code user_id} 过滤），
 * 与商家维度正交。商家订单查询是新增能力，故独立成接口与实现，
 * 不触碰任何既有方法签名与实现。
 * </p>
 *
 * <h3>查询范围（权限边界，不可由客户端指定）</h3>
 * <pre>
 * 当前登录商家 id（由 Controller 从登录态取得）
 *   → tb_shop.owner_id = merchantId          （命中 idx_shop_owner）
 *   → shopIds
 *   → tb_voucher.shop_id IN (shopIds)        （命中 fk_voucher_shop）
 *   → voucherIds
 *   → tb_voucher_order.voucher_id IN (voucherIds)   （命中 fk_order_voucher）
 *   → 分页结果
 * </pre>
 * <p>
 * <b>铁律</b>：查询范围<b>必须</b>从当前登录态推导，
 * <b>绝不接受</b>客户端传入的 {@code shopId} / {@code voucherId} / {@code ownerId}
 * 作为权限依据（本接口签名中也不包含这些参数）。
 * 无店铺的商家返回空列表，<b>不得</b>退化为「查询全部订单」。
 * </p>
 *
 * <h3>权限契约（由调用方 Controller 保证）</h3>
 * <table border="1">
 *   <tr><th>调用方</th><th>结果</th></tr>
 *   <tr><td>匿名</td><td>401 + {@code 10001 AUTH_REQUIRED}</td></tr>
 *   <tr><td>普通用户（{@code role != 1}）</td><td>403 + {@code 40003 MERCHANT_ONLY}</td></tr>
 *   <tr><td>商家（{@code role == 1}）</td><td>继续查询，仅返回自己店铺的订单</td></tr>
 * </table>
 * <p>
 * 注意：{@code /voucher-orders/**} 虽受 {@code LoginInterceptor} 约束（匿名 401），
 * 但<b>不受</b> {@code WriteAuthInterceptor} 约束（后者只覆盖 {@code /shops/**}、
 * {@code /vouchers/**} 的写方法）。因此调用方<b>必须显式</b>调用
 * {@code MerchantShopGuard.assertMerchant()} 完成商家角色校验，
 * 并将返回的 id 作为 {@code merchantId} 传入本方法
 * （与 {@code GET /shops/my}、{@code GET /vouchers/my} 的既有模式一致）。
 * </p>
 *
 * <h3>分页契约</h3>
 * <ul>
 *   <li>{@code current} 默认 {@code 1}，{@code size} 默认 {@code 10}（由 Controller 提供默认值）；</li>
 *   <li>使用 MyBatis-Plus {@code Page}，<b>不自行实现分页、不自行 clamp</b>；</li>
 *   <li>全局分页上限由 {@code MybatisConfig.setMaxLimit(100)} 处理
 *       （{@code size > 100} 或 {@code size < 0} 会被插件钳制为 100，{@code size == 0} 返回空）；</li>
 *   <li><b>不引用</b> {@code SystemConstants.MAX_PAGE_SIZE}（该项存在语义漂移，本阶段不修）。</li>
 * </ul>
 *
 * <h3>status 契约</h3>
 * <ul>
 *   <li>{@code status} 为 {@code null}：查询该商家<b>全部</b>订单；</li>
 *   <li>{@code status} 非 {@code null}：仅作为数据库查询条件（{@code eq("status", status)}）；</li>
 *   <li>状态取值沿用既有 {@code OrderStatus}（1 待支付 / 2 已支付 / 3 已核销 /
 *       4 已取消 / 5 已过期 / 6 已退款），<b>不新增状态、不修改状态语义</b>；
 *       其中 {@code 5 EXPIRED} 的写入路径为 {@code payVoucherOrder}：对已过期的
 *       秒杀券发起支付时以 CAS 收敛到终态，避免订单永远卡在 PENDING_PAYMENT。</li>
 * </ul>
 *
 * <h3>本阶段（3.4-B.1）范围</h3>
 * <p>
 * 本接口<b>只定义契约</b>。实现类（{@code MerchantVoucherOrderQueryServiceImpl}）
 * 在 3.4-B.2 落地；Controller 端点 {@code GET /voucher-orders/merchant}
 * 在 3.4-B.3 落地。
 * </p>
 *
 * @see com.hmdp.dto.MerchantVoucherOrderItemDTO
 */
public interface IMerchantVoucherOrderQueryService {

    /**
     * 分页查询指定商家名下所有店铺的优惠券订单。
     *
     * @param merchantId 当前登录商家用户 id（由 Controller 从
     *                   {@code MerchantShopGuard.assertMerchant()} 取得，
     *                   <b>不得</b>使用客户端传入的参数）
     * @param current    页码，从 1 开始（Controller 默认 1）
     * @param size       每页条数（Controller 默认 10；全局上限 100 由 MyBatis-Plus 处理）
     * @param status     可选订单状态过滤（{@code null} 表示不过滤）；取值见 {@code OrderStatus}
     * @return 分页结果：{@code data} 为 {@link MerchantVoucherOrderItemDTO} 列表，
     *         {@code total} 为该商家订单总数；商家无店铺或无非匹配订单时返回空列表
     */
    Result<List<MerchantVoucherOrderItemDTO>> queryMerchantOrders(Long merchantId,
                                                                  Integer current,
                                                                  Integer size,
                                                                  Integer status);
}
