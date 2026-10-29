package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 优惠券订单消息 DTO
 * <p>
 * 用于秒杀下单场景的 Redis Stream 消息传递，包含预分配的订单 ID、
 * 下单用户 ID 和秒杀优惠券 ID，由后台消费者异步落库。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 预分配的订单 ID（由 RedisIdWorker 在 Lua 原子扣减成功后生成） */
    private Long orderId;

    /** 下单用户 ID */
    private Long userId;

    /** 秒杀优惠券 ID */
    private Long voucherId;
}
