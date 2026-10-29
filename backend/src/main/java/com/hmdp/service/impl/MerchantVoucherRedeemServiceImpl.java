package com.hmdp.service.impl;

import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.service.IMerchantVoucherRedeemService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.OrderStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 商家核销优惠券订单实现（Phase 3.4-A）
 * <p>
 * 唯一能力：{@code PAID(2) -> USED(3)}，写入 {@code use_time}。
 * </p>
 *
 * <h3>实现要点</h3>
 * <ul>
 *   <li><b>单条条件 UPDATE（CAS）</b>：状态判断与写入在同一条 SQL 内完成，
 *       不采用 {@code getById -> if -> setStatus -> updateById} 的读-判断-写模式
 *       （该模式在并发下会双写）。</li>
 *   <li><b>逻辑删除自动生效</b>：{@code VoucherOrder.isDeleted} 标注了
 *       {@code @TableLogic}，全局配置 {@code logic-delete-field: is_deleted}，
 *       MyBatis-Plus 会在 WHERE 自动追加 {@code is_deleted = 0}，
 *       因此已逻辑删除的订单无法被核销（真实 SQL 已在测试中取证）。</li>
 *   <li><b>无 @Transactional</b>：本方法只有一条 UPDATE 语句，本身即原子，
 *       且不涉及库存 / Redis / 订单占位等需要回滚的联动副作用。
 *       加事务只会引入无意义的代理与连接开销（秒杀链路的
 *       {@code cancel/refund} 需要事务是因为它们同时回补 DB 库存与 Redis）。</li>
 *   <li><b>零 Redis / MQ 副作用</b>：核销不改变库存、不改变秒杀占位集合、
 *       不产生 Stream 消息，故不动 {@code SECKILL_*} 任何键。</li>
 * </ul>
 *
 * <h3>为什么注入 IVoucherOrderService 而不是新建 Mapper</h3>
 * <p>
 * 复用既有 {@code IService} 的链式 UpdateWrapper 能力（{@code update().set(...).eq(...).update()}），
 * 与 {@code VoucherOrderServiceImpl} 中 pay/cancel/refund 的既有写法保持一致，
 * 无需新增 Mapper 方法或 XML，也不修改任何冻结文件。
 * </p>
 */
@Slf4j
@Service
public class MerchantVoucherRedeemServiceImpl implements IMerchantVoucherRedeemService {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * {@inheritDoc}
     */
    @Override
    public Result<Void> redeem(Long orderId) {
        // 单条 CAS：只有 status 仍为 PAID(2) 的那一次请求能命中并改为 USED(3)。
        // 逻辑删除条件（is_deleted = 0）由 @TableLogic 自动追加到 WHERE。
        boolean updated = voucherOrderService.update()
                .set("status", OrderStatus.USED)
                .set("use_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", OrderStatus.PAID)
                .update();

        if (!updated) {
            // 影响行数为 0：订单已被核销 / 状态不是「已支付」/ 并发竞争失败。
            // 统一返回 30009，不泄露「订单是否存在」「被谁先核销」等细节。
            log.warn("核销失败（影响行数为 0）：orderId={}, 期望状态=PAID(2)", orderId);
            return Result.fail(ErrorCode.ORDER_STATUS_INVALID);
        }
        return Result.ok();
    }
}
