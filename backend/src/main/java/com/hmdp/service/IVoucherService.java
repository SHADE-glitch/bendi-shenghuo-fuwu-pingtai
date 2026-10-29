package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherSaveRequest;
import com.hmdp.dto.VoucherUpdateRequest;
import com.hmdp.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 优惠券服务接口
 * <p>提供商铺优惠券查询、新增及秒杀优惠券新增能力。</p>
 */
public interface IVoucherService extends IService<Voucher> {

    /**
     * 查询指定商铺的优惠券列表
     *
     * @param shopId 商铺 ID
     * @return 优惠券列表结果
     */
    Result queryVoucherOfShop(Long shopId);

    /**
     * 新增普通优惠券
     *
     * @param request 新增优惠券请求参数
     * @return 包含优惠券 ID 的结果
     */
    Result<Long> addVoucher(VoucherSaveRequest request);

    /**
     * 新增秒杀优惠券
     *
     * @param request 新增优惠券请求参数（含秒杀信息）
     * @return 包含优惠券 ID 的结果
     */
    Result<Long> addSeckillVoucher(VoucherSaveRequest request);

    /**
     * 查询指定商家名下<b>全部店铺</b>的优惠券列表（Merchant「我的优惠券」）
     * <p>
     * 与消费者接口 {@link #queryVoucherOfShop(Long)} 的区别：
     * <ul>
     *   <li>按 {@code tb_shop.owner_id} 聚合该商家所有店铺，而非单个 shopId；</li>
     *   <li><b>不过滤 {@code status}</b>——商家必须能看到自己已下架/过期的券；</li>
     *   <li>秒杀券补充 {@code stock}/{@code beginTime}/{@code endTime}
     *       （取自 {@code tb_seckill_voucher}，这三个字段在 {@code Voucher} 上为非持久化字段）。</li>
     * </ul>
     * </p>
     *
     * @param ownerId 当前登录商家 id（由 Controller 从登录态注入，不接受客户端参数）
     * @return 该商家名下所有店铺的优惠券列表
     */
    Result<List<Voucher>> queryVouchersByOwner(Long ownerId);

    /**
     * 更新优惠券的<b>可修改字段</b>（标题/副标题/规则/支付金额/抵扣金额/状态）
     * <p>
     * 仅写白名单字段；归属（{@code shopId}）、类型（{@code type}）、
     * 秒杀库存与起止时间均不在更新范围内。
     * 调用方（Controller）必须<b>先完成 owner 归属校验</b>。
     * </p>
     *
     * @param voucherId 优惠券 id
     * @param request   待更新的字段
     * @return 成功返回 ok
     */
    Result<Void> updateOwnVoucher(Long voucherId, VoucherUpdateRequest request);
}
