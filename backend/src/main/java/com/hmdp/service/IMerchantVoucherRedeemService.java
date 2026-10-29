package com.hmdp.service;

import com.hmdp.dto.Result;

/**
 * 商家核销优惠券订单服务接口（Phase 3.4-A）
 * <p>
 * <b>职责</b>：把一张「已支付（PAID=2）」的订单置为「已使用（USED=3）」，
 * 并写入核销时间 {@code use_time}。这是商家侧对用户券的核销动作。
 * </p>
 *
 * <h3>为什么是独立接口（而不是扩展 IVoucherOrderService）</h3>
 * <p>
 * 既有 {@code IVoucherOrderService} 的实现类 {@code VoucherOrderServiceImpl}
 * 承载秒杀 / Redis Stream / Pending List / Lua 等核心链路，已被冻结。
 * 核销是「用户下单主链路」之外的新增能力，故独立成接口与实现，
 * 不触碰任何既有方法签名与实现。
 * </p>
 *
 * <h3>边界（D1–D4，本阶段锁定）</h3>
 * <ul>
 *   <li>D1：仅允许 {@code PAID(2) -> USED(3)}，其它状态一律拒绝（{@code 30009}）。</li>
 *   <li>D2：MVP <b>不</b>校验 {@code voucher.status} 与秒杀 {@code end_time}（不引入新失败分支）。</li>
 *   <li>D3：只按 {@code orderId} 核销，不提供商家订单列表。</li>
 *   <li>D4：不可撤销（无 {@code USED(3) -> PAID(2)} 反向操作）。</li>
 * </ul>
 *
 * <h3>并发与幂等</h3>
 * <p>
 * 状态流转由 <b>单条条件 UPDATE（CAS）</b> 保证原子性：
 * 只有当前状态确为 {@code PAID} 的那一次请求能把行改为 {@code USED}，
 * 并发/重复请求影响行数为 0，返回 {@code 30009}，不会出现「双核销」。
 * </p>
 */
public interface IMerchantVoucherRedeemService {

    /**
     * 核销指定订单：{@code PAID(2) -> USED(3)}。
     * <p>
     * <b>前置条件由调用方（Controller）保证</b>：当前登录用户是商家，
     * 且该订单对应的 Voucher 归属于该商家的店铺。
     * 本方法只负责「状态机 + 原子落库」，不感知店铺、不感知当前登录用户。
     * </p>
     *
     * @param orderId 目标订单 id
     * @return 成功 {@code ok}；状态不是「已支付」（含并发竞争失败）→ {@code 30009 ORDER_STATUS_INVALID}
     */
    Result<Void> redeem(Long orderId);
}
