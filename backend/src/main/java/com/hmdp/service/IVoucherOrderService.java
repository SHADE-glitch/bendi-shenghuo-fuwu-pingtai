package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.VoucherOrder;

/**
 * 优惠券订单服务接口
 * <p>提供秒杀下单、普通购买、订单创建及订单状态查询能力，支持基于 Redis Stream 的异步落库。</p>
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    /**
     * 秒杀下单（异步模式）
     * <p>流程：Redis Lua 原子预检 → 写入 Redis Stream → 立即返回“处理中”</p>
     *
     * @param voucherId 秒杀优惠券 ID
     * @return 下单结果
     */
    Result seckillVoucher(Long voucherId);

    /**
     * 普通优惠券购买（同步模式）
     *
     * @param voucherId 优惠券 ID
     * @return 购买结果
     */
    Result purchaseVoucher(Long voucherId);

    /**
     * 直接创建订单（供普通同步购买复用）
     *
     * @param voucherId 优惠券 ID
     * @return 订单创建结果
     */
    Result createVoucherOrder(Long voucherId);

    /**
     * 消费 Redis Stream 订单消息并异步落库
     * <p>内部实现幂等校验、库存扣减及订单创建。</p>
     *
     * @param msg 秒杀订单消息，含预分配 orderId、userId、voucherId
     */
    void createOrderByMessage(VoucherOrderMessage msg);

    /**
     * 查询指定订单的状态
     *
     * @param orderId 订单 ID
     * @return 订单状态结果
     */
    Result getOrderStatus(Long orderId);

    /**
     * 查询当前用户“我的优惠券”列表
     *
     * @param tab     标签：unused|used|expired
     * @param current 页码
     * @param size    每页大小
     * @return 优惠券列表
     */
    Result queryMyVouchers(String tab, Integer current, Integer size);

    /**
     * 查询当前用户订单详情
     *
     * @param orderId 订单 ID
     * @return 订单详情
     */
    Result queryOrderDetail(Long orderId);

    /**
     * 伪支付：模拟余额/微信/支付宝支付待支付订单
     *
     * @param orderId 订单 ID
     * @param payType 支付方式：1余额；2微信；3支付宝
     * @return 支付结果
     */
    Result payVoucherOrder(Long orderId, Integer payType);

    /**
     * 取消待支付订单（恢复库存与秒杀占位）
     *
     * @param orderId 订单 ID
     * @return 取消结果
     */
    Result cancelVoucherOrder(Long orderId);

    /**
     * 退款：已支付未核销订单退款并恢复库存
     *
     * @param orderId 订单 ID
     * @return 退款结果
     */
    Result refundVoucherOrder(Long orderId);

    /**
     * 删除已取消/已退款订单记录
     *
     * @param orderId 订单 ID
     * @return 删除结果
     */
    Result deleteVoucherOrder(Long orderId);
}
