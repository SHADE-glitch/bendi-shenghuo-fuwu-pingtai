package com.hmdp.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀库存补偿器单测（P1-3）
 *
 * <p>
 * <b>性质：纯单元测试，零基础设施。</b>{@link StringRedisTemplate} 为 Mockito 桩，
 * 事务同步用 Spring 的静态 {@link TransactionSynchronizationManager}（init/clear）
 * 手工驱动，不启动 Spring 上下文、不需要 DataSource。覆盖 P1-3 的核心语义：
 * </p>
 * <ul>
 *   <li>补偿注册在事务提交后（afterCommit），提交前绝不触碰 Redis；</li>
 *   <li>事务回滚（afterCompletion(ROLLED_BACK)）时回调不触发，Redis 零交互；</li>
 *   <li>无事务上下文时立即执行（与 {@code registerAfterCommit} 既有口径一致）；</li>
 *   <li>执行失败有界重试，仍失败转死信队列；脚本幂等跳过（返回 0）视为成功。</li>
 * </ul>
 *
 * <p>
 * Lua 脚本自身的原子性（INCR+SREM+SET NX 一段执行）无法在无 Redis 的环境验证，
 * 由集成链路（取消/退款 → 补偿 → 再次抢购）在 {@code VoucherOrderCancelRepurchaseTest}
 * 与 CI 的 MySQL+Redis 环境下覆盖。
 * </p>
 */
@DisplayName("秒杀库存补偿器（P1-3）")
class SeckillStockCompensatorTest {

    private static final long ORDER_ID = 920001L;
    private static final long VOUCHER_ID = 920002L;
    private static final long USER_ID = 920003L;
    private static final List<String> EXPECTED_KEYS = List.of(
            RedisConstants.SECKILL_STOCK_KEY + VOUCHER_ID,
            RedisConstants.SECKILL_ORDER_KEY + VOUCHER_ID,
            RedisConstants.SECKILL_ROLLBACK_KEY + ORDER_ID);

    private StringRedisTemplate stringRedisTemplate;
    private ListOperations<String, String> listOperations;
    private SeckillStockCompensator compensator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        listOperations = mock(ListOperations.class);
        when(stringRedisTemplate.opsForList()).thenReturn(listOperations);

        compensator = new SeckillStockCompensator(stringRedisTemplate);
        // 复刻 @PostConstruct：加载 lua/seckill_rollback.lua（不发去 Redis，只是脚本对象）
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new org.springframework.scripting.support.ResourceScriptSource(
                new ClassPathResource("lua/seckill_rollback.lua")));
        script.setResultType(Long.class);
        inject(compensator, "rollbackScript", script);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("事务内注册：提交前不碰 Redis，afterCommit 后才执行补偿")
    void registersAfterCommitAndExecutesOnlyOnCommit() {
        TransactionSynchronizationManager.initSynchronization();
        when(stringRedisTemplate.execute(any(), anyList(), any(), any())).thenReturn(1L);

        compensator.compensateAfterCommit(VOUCHER_ID, USER_ID, ORDER_ID);

        // 注册了同步回调，但提交前 Redis 零交互
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        verify(stringRedisTemplate, times(0)).execute(any(), anyList(), any(), any());

        // 模拟事务提交
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }

        verify(stringRedisTemplate, times(1))
                .execute(any(), anyList(), eq(String.valueOf(USER_ID)),
                        eq(String.valueOf(RedisConstants.SECKILL_ROLLBACK_MARKER_TTL)));
    }

    @Test
    @DisplayName("事务回滚：afterCompletion(ROLLED_BACK) 不触发补偿，Redis 零交互")
    void rollbackNeverCompensates() {
        TransactionSynchronizationManager.initSynchronization();

        compensator.compensateAfterCommit(VOUCHER_ID, USER_ID, ORDER_ID);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);

        // 模拟事务回滚：只走 afterCompletion，不走 afterCommit
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        verify(stringRedisTemplate, times(0)).execute(any(), anyList(), any(), any());
        verify(stringRedisTemplate, times(0)).opsForList();
    }

    @Test
    @DisplayName("无事务上下文：立即执行补偿")
    void executesImmediatelyWithoutTransaction() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        when(stringRedisTemplate.execute(any(), anyList(), any(), any())).thenReturn(1L);

        compensator.compensateAfterCommit(VOUCHER_ID, USER_ID, ORDER_ID);

        verify(stringRedisTemplate, times(1)).execute(any(), anyList(), any(), any());
    }

    @Test
    @DisplayName("幂等跳过（脚本返回 0）视为成功，不重试、不进死信")
    void idempotentSkipCountsAsSuccess() {
        when(stringRedisTemplate.execute(any(), anyList(), any(), any())).thenReturn(0L);

        boolean ok = compensator.compensateNow(VOUCHER_ID, USER_ID, ORDER_ID);

        assertThat(ok).isTrue();
        verify(stringRedisTemplate, times(1)).execute(any(), anyList(), any(), any());
        verify(listOperations, times(0)).rightPush(any(), any());
    }

    @Test
    @DisplayName("前两次失败、第三次成功：重试后完成，不进死信")
    void retriesUntilSuccess() {
        when(stringRedisTemplate.execute(any(), anyList(), any(), any()))
                .thenReturn(null)
                .thenReturn(null)
                .thenReturn(1L);

        boolean ok = compensator.compensateNow(VOUCHER_ID, USER_ID, ORDER_ID);

        assertThat(ok).isTrue();
        verify(stringRedisTemplate, times(3)).execute(any(), anyList(), any(), any());
        verify(listOperations, times(0)).rightPush(any(), any());
    }

    @Test
    @DisplayName("三次全部失败：转死信队列（orderId|voucherId|userId）并返回 false")
    void allAttemptsFailGoesToDlq() {
        when(stringRedisTemplate.execute(any(), anyList(), any(), any())).thenReturn(null);

        boolean ok = compensator.compensateNow(VOUCHER_ID, USER_ID, ORDER_ID);

        assertThat(ok).isFalse();
        verify(stringRedisTemplate, times(3)).execute(any(), anyList(), any(), any());
        // 死信元素携带补卡所需全部信息，重放时凭幂等标记不会重复加库存
        verify(listOperations).rightPush(
                RedisConstants.SECKILL_ROLLBACK_DLQ_KEY, ORDER_ID + "|" + VOUCHER_ID + "|" + USER_ID);
    }

    @Test
    @DisplayName("脚本参数：三个 key（stock/orders/rollback 标记）+ userId + TTL")
    void passesExpectedKeysAndArgs() {
        when(stringRedisTemplate.execute(any(), anyList(), any(), any())).thenReturn(1L);

        compensator.compensateNow(VOUCHER_ID, USER_ID, ORDER_ID);

        verify(stringRedisTemplate).execute(any(), eq(EXPECTED_KEYS),
                eq(String.valueOf(USER_ID)), eq(String.valueOf(RedisConstants.SECKILL_ROLLBACK_MARKER_TTL)));
    }

    @Test
    @DisplayName("重试等待被中断：恢复中断状态并抛出，不再静默吞掉")
    void interruptDuringRetryAborts() {
        when(stringRedisTemplate.execute(any(), anyList(), any(), any())).thenReturn(null);
        Thread.currentThread().interrupt(); // 让 sleep 立刻抛 InterruptedException

        assertThatThrownBy(() -> compensator.compensateNow(VOUCHER_ID, USER_ID, ORDER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.interrupted()).isTrue(); // 中断位已恢复
    }

    // ---------------------------------------------------------------

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
