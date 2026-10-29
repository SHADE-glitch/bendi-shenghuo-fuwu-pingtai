package com.hmdp.utils;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀库存补偿器（P1-3）
 *
 * <p>
 * 取消 / 退款回补库存时，Redis 侧补偿（库存 +1 + 移除用户占位）必须满足三条：
 * </p>
 * <ul>
 *   <li><b>事务后置</b>：DB 状态流转（PENDING→CANCELLED / PAID→REFUNDED）提交之后才允许动
 *       Redis。旧实现在事务内直接 INCR/SREM：DB 随后回滚时 Redis 已 +1，库存永久虚高；
 *       DB 提交但 Redis 失败时库存永久虚低。两种漂移都没有自愈路径。</li>
 *   <li><b>原子</b>：INCR 与 SREM 合并进一段 Lua（{@code lua/seckill_rollback.lua}），
 *       消除「库存回了、占位还在」的中间态。</li>
 *   <li><b>幂等 + 可重试</b>：脚本内以 {@code seckill:rollback:{orderId}} SET NX 作幂等标记，
 *       响应丢失导致的重放不会重复加库存；执行失败时有界重试，仍失败转
 *       {@link RedisConstants#SECKILL_ROLLBACK_DLQ_KEY} 死信队列并 ERROR 告警，
 *       凭幂等标记可安全地人工补卡 / 后续对账任务重放。</li>
 * </ul>
 *
 * <p>
 * 取舍说明：本项目无调度基建，补偿死信没有自动消费者，靠运维或后续对账任务触发重放；
 * 这是「最小正确版本」——它保证<b>不回滚已提交的 DB、不重复补偿、失败可见</b>，
 * 但不承诺失败后秒级自愈。进程在 DB 提交后、afterCommit 回调前崩溃仍会丢失一次补偿
 * （任何非 Outbox 方案的共同边界），需要时可按落库补偿的思路升级为 Outbox 模式。
 * </p>
 */
@Slf4j
@Component
public class SeckillStockCompensator {

    /** 内联重试次数（首次 + 2 次重试）。重试只覆盖 Redis 瞬时抖动，不做无限重试。 */
    private static final int MAX_ATTEMPTS = 3;
    /** 重试退避基数（毫秒），第 n 次重试前休眠 base * n。 */
    private static final long RETRY_BACKOFF_BASE_MS = 100L;

    private final StringRedisTemplate stringRedisTemplate;

    private DefaultRedisScript<Long> rollbackScript;

    public SeckillStockCompensator(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @PostConstruct
    private void init() {
        rollbackScript = new DefaultRedisScript<>();
        rollbackScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("lua/seckill_rollback.lua")));
        rollbackScript.setResultType(Long.class);
    }

    /**
     * 在事务提交后执行补偿；无事务上下文（如单测直调）时立即执行。
     * 与 {@code VoucherServiceImpl.registerAfterCommit} 同一口径。
     *
     * @param voucherId 秒杀券 ID
     * @param userId    下单用户 ID
     * @param orderId   订单 ID（幂等标记维度）
     */
    public void compensateAfterCommit(Long voucherId, Long userId, Long orderId) {
        Runnable action = () -> compensateNow(voucherId, userId, orderId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
            return;
        }
        action.run();
    }

    /**
     * 立即执行补偿（带内联重试 + 死信兜底）。
     * <p>
     * 幂等由 Lua 内的 SET NX 标记保证：同一 orderId 重复调用（重试、补卡、重放）
     * 只有第一次真正修改 Redis，后续调用直接跳过。
     * </p>
     *
     * @return true = 本次完成了补偿（或被幂等跳过）；false = 失败并已转入死信队列
     */
    public boolean compensateNow(Long voucherId, Long userId, Long orderId) {
        List<String> keys = Arrays.asList(
                RedisConstants.SECKILL_STOCK_KEY + voucherId,
                RedisConstants.SECKILL_ORDER_KEY + voucherId,
                RedisConstants.SECKILL_ROLLBACK_KEY + orderId);
        String ttl = String.valueOf(RedisConstants.SECKILL_ROLLBACK_MARKER_TTL);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Long executed = stringRedisTemplate.execute(
                        rollbackScript, keys, userId.toString(), ttl);
                // executed == null：脚本执行异常（连接/超时）。0 = 幂等跳过，1 = 已补偿。
                if (executed != null) {
                    if (executed == 1L) {
                        log.info("秒杀库存补偿完成, orderId={}, voucherId={}, userId={}",
                                orderId, voucherId, userId);
                    } else {
                        log.info("秒杀库存补偿幂等跳过（该订单已补偿过）, orderId={}", orderId);
                    }
                    return true;
                }
                log.warn("秒杀库存补偿脚本返回异常（第 {}/{} 次）, orderId={}",
                        attempt, MAX_ATTEMPTS, orderId);
            } catch (Exception e) {
                log.warn("秒杀库存补偿执行失败（第 {}/{}）, orderId={}, voucherId={}",
                        attempt, MAX_ATTEMPTS, orderId, voucherId, e);
            }
            if (attempt < MAX_ATTEMPTS) {
                sleepBeforeRetry(attempt);
            }
        }

        // 内联重试全部失败：转死信队列，保证「失败可见、可补卡」而非静默丢弃。
        // 死信元素格式：orderId|voucherId|userId，重放时调用 compensateNow 即可（幂等）。
        String dlqEntry = orderId + "|" + voucherId + "|" + userId;
        try {
            stringRedisTemplate.opsForList().rightPush(
                    RedisConstants.SECKILL_ROLLBACK_DLQ_KEY, dlqEntry);
            log.error("秒杀库存补偿重试 {} 次仍失败，已转入死信队列待补卡: {}",
                    MAX_ATTEMPTS, dlqEntry);
        } catch (Exception e) {
            // 死信也写不进去（Redis 整体不可用）：只剩日志这一条线索。
            log.error("秒杀库存补偿失败且死信队列写入失败，需人工对账: {}", dlqEntry, e);
        }
        return false;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            TimeUnit.MILLISECONDS.sleep(RETRY_BACKOFF_BASE_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 恢复中断后放弃剩余重试：调用方（请求线程）已失去等待意愿
            log.warn("秒杀库存补偿重试等待被中断，放弃剩余重试");
            throw new IllegalStateException("秒杀库存补偿重试被中断", e);
        }
    }
}
