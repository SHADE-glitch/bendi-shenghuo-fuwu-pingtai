package com.hmdp.test;

import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H1 回归测试：Stream 毒消息必须走「DLQ + ACK」终态，不得进入无限重试。
 *
 * <p>覆盖两类毒消息：</p>
 * <ul>
 *   <li><b>T-H1-1</b>：非数字字段（{@code orderId=abc}）—— 改动前会抛
 *       {@code NumberFormatException} 冒泡到 pending 循环的 1s 退避重试，永不 ACK。</li>
 *   <li><b>T-H1-2</b>：字段缺失（A4 原有语义回归）。</li>
 * </ul>
 *
 * <p>核心断言：消息被消费后 <b>Pending 不增加</b>（即被 ACK）且 <b>DLQ 增长</b>。
 * 若毒消息未被 ACK，Pending 会长期停在 baseline+1，本用例超时失败。</p>
 *
 * <p>运行命令：{@code mvn -o test -Dtest=SeckillStreamPoisonMessageTest -DfailIfNoTests=false}</p>
 */
@Slf4j
@SpringBootTest
@DisplayName("H1 秒杀 Stream 毒消息 DLQ 终态回归")
public class SeckillStreamPoisonMessageTest {

    /** 与 VoucherOrderServiceImpl.SECKILL_STREAM_DLQ_KEY 保持一致（该常量为 private） */
    private static final String DLQ_KEY = "stream:seckill:orders:dlq";

    private static final String STREAM_KEY = RedisConstants.SECKILL_STREAM_KEY;
    private static final String GROUP = RedisConstants.SECKILL_STREAM_GROUP;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** DLQ 基线长度，tearDown 时回滚到该长度，避免污染其它用例 */
    private long dlqBaseline;

    @BeforeEach
    void setUp() {
        ensureStreamGroup();
        dlqBaseline = dlqSize();
    }

    @AfterEach
    void tearDown() {
        // 只回收本用例新增的 DLQ 条目
        long size = dlqSize();
        while (size > dlqBaseline) {
            stringRedisTemplate.opsForList().rightPop(DLQ_KEY);
            size--;
        }
    }

    // -----------------------------------------------------------------
    // 用例
    // -----------------------------------------------------------------

    @Test
    @DisplayName("T-H1-1 非数字 orderId：连续两条均转 DLQ + ACK，消费者不被阻塞")
    void nonNumericPoisonMessagesGoToDlqAndAck() throws Exception {
        long pendingBaseline = pendingTotal();
        long dlqBefore = dlqSize();

        addPoison(Map.of("orderId", "abc", "userId", "1", "voucherId", "1"));
        addPoison(Map.of("orderId", "xyz", "userId", "1", "voucherId", "1"));

        boolean settled = awaitSettled(pendingBaseline, dlqBefore, dlqBefore + 2, 15000);

        assertThat(settled)
                .as("两条非数字毒消息应在 15s 内全部被 ACK 并转入 DLQ（改动前会无限重试）")
                .isTrue();
        assertThat(dlqSize())
                .as("两条毒消息都应转存 DLQ")
                .isEqualTo(dlqBefore + 2);
        assertThat(pendingTotal())
                .as("毒消息必须被 ACK，Pending 不得残留")
                .isLessThanOrEqualTo(pendingBaseline);
    }

    @Test
    @DisplayName("T-H1-2 字段缺失：转 DLQ + ACK（A4 语义回归）")
    void missingFieldPoisonMessageGoesToDlqAndAck() throws Exception {
        long pendingBaseline = pendingTotal();
        long dlqBefore = dlqSize();

        addPoison(Map.of("init", "1"));

        boolean settled = awaitSettled(pendingBaseline, dlqBefore, dlqBefore + 1, 15000);

        assertThat(settled)
                .as("字段缺失的脏消息应在 15s 内被 ACK 并转入 DLQ")
                .isTrue();
        assertThat(dlqSize()).isEqualTo(dlqBefore + 1);
        assertThat(pendingTotal()).isLessThanOrEqualTo(pendingBaseline);
    }

    // -----------------------------------------------------------------
    // 辅助
    // -----------------------------------------------------------------

    private RecordId addPoison(Map<String, String> fields) {
        Map<String, String> payload = new HashMap<>(fields);
        RecordId id = stringRedisTemplate.opsForStream().add(STREAM_KEY, payload);
        assertThat(id).as("XADD 毒消息失败").isNotNull();
        return id;
    }

    /**
     * 等待消费侧收敛：Pending 回落到基线以内，且 DLQ 增长到期望条数。
     */
    private boolean awaitSettled(long pendingBaseline, long dlqBefore, long dlqExpected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (pendingTotal() <= pendingBaseline && dlqSize() >= dlqExpected) {
                return true;
            }
            Thread.sleep(300);
        }
        log.warn("等待收敛超时: pendingBaseline={}, dlqBefore={}, dlqExpected={}, 实际 pending={}, dlq={}",
                pendingBaseline, dlqBefore, dlqExpected, pendingTotal(), dlqSize());
        return false;
    }

    private long pendingTotal() {
        var pending = stringRedisTemplate.opsForStream().pending(STREAM_KEY, GROUP);
        Long total = pending == null ? null : pending.getTotalPendingMessages();
        return total == null ? 0L : total;
    }

    private long dlqSize() {
        Long size = stringRedisTemplate.opsForList().size(DLQ_KEY);
        return size == null ? 0L : size;
    }

    private void ensureStreamGroup() {
        try {
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(STREAM_KEY))) {
                stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP);
            }
        } catch (Exception ignored) {
            // 组已存在时忽略（BUSYGROUP）
        }
    }
}
