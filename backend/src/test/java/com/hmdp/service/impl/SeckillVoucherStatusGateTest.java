package com.hmdp.service.impl;

import com.hmdp.dto.CreateVoucherOrderResultDTO;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.connection.stream.RecordId;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀下单的券主表状态闸门单测（P1-2）
 *
 * <p>
 * <b>性质：纯单元测试，零基础设施。</b>{@link VoucherOrderServiceImpl} 直接 new
 * （不触发 {@code @PostConstruct}，因此不注册 Stream 消费线程），协作者全部
 * Mockito 桩。钉住一件事：<b>秒杀链路与普通购券链路一样校验 {@code tb_voucher.status}</b>，
 * 商家下架（2）/ 置过期（3）后即使 {@code end_time} 未到也立即停售。
 * </p>
 *
 * <p>
 * 与真实 Redis/DB 的端到端语义（Lua 预扣、Stream 落库）由既有集成测试
 * {@code VoucherOrderTest} 覆盖；本类只钉住「状态闸门」这一新增判定。
 * </p>
 */
@DisplayName("秒杀下单券状态闸门（P1-2）")
class SeckillVoucherStatusGateTest {

    private static final long VOUCHER_ID = 910001L;
    private static final long USER_ID = 910002L;
    private static final List<String> SECKILL_KEYS = Arrays.asList(
            RedisConstants.SECKILL_STOCK_KEY + VOUCHER_ID,
            RedisConstants.SECKILL_ORDER_KEY + VOUCHER_ID);

    private VoucherOrderServiceImpl voucherOrderService;
    private ISeckillVoucherService seckillVoucherService;
    private IVoucherService voucherService;
    private StringRedisTemplate stringRedisTemplate;
    private RedissonClient redissonClient;
    private RedisIdWorker redisIdWorker;
    private DefaultRedisScript<Long> stubScript;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        seckillVoucherService = mock(ISeckillVoucherService.class);
        voucherService = mock(IVoucherService.class);
        stringRedisTemplate = mock(StringRedisTemplate.class);
        redissonClient = mock(RedissonClient.class);
        redisIdWorker = mock(RedisIdWorker.class);
        // 放行路径会走 DB 一人一单 count 预检，baseMapper 不能为空
        VoucherOrderMapper voucherOrderMapper = mock(VoucherOrderMapper.class);
        when(voucherOrderMapper.selectCount(any())).thenReturn(0L);
        // @PostConstruct 未执行，seckillScript 为 null；塞一个桩脚本对象，让 execute 的
        // 参数匹配能命中（模板本身是 Mock，脚本不会真的发去 Redis）
        stubScript = new DefaultRedisScript<>();
        stubScript.setScriptText("return 0");
        stubScript.setResultType(Long.class);

        voucherOrderService = new VoucherOrderServiceImpl();
        inject(voucherOrderService, "seckillVoucherService", seckillVoucherService);
        inject(voucherOrderService, "voucherService", voucherService);
        inject(voucherOrderService, "stringRedisTemplate", stringRedisTemplate);
        inject(voucherOrderService, "redissonClient", redissonClient);
        inject(voucherOrderService, "redisIdWorker", redisIdWorker);
        inject(voucherOrderService, "baseMapper", voucherOrderMapper);
        inject(voucherOrderService, "seckillScript", stubScript);

        UserDTO user = new UserDTO();
        user.setId(USER_ID);
        user.setNickName("闸门单测用户");
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    // ---------------------------------------------------------------
    // 拒绝路径：状态闸门
    // ---------------------------------------------------------------

    @Test
    @DisplayName("秒杀券不存在 → 30000，不触碰 Redis/锁")
    void rejectsWhenSeckillVoucherMissing() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(null);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.VOUCHER_NOT_FOUND.getCode());
        verify(stringRedisTemplate, never()).opsForValue();
        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    @DisplayName("券主表行不存在 → 30000（与 createVoucherOrder 口径一致）")
    void rejectsWhenVoucherRowMissing() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeSeckillVoucher());
        when(voucherService.getById(VOUCHER_ID)).thenReturn(null);

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.VOUCHER_NOT_FOUND.getCode());
        verify(stringRedisTemplate, never()).opsForValue();
        verify(redissonClient, never()).getLock(anyString());
    }

    @Test
    @DisplayName("商家下架（status=2）→ 30000，即使 end_time 未到也不放行")
    void rejectsWhenVoucherOffShelf() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeSeckillVoucher());
        when(voucherService.getById(VOUCHER_ID)).thenReturn(voucherWithStatus(2));

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.VOUCHER_NOT_FOUND.getCode());
        // 闸门必须在互斥锁/Lua 预扣之前：被拒时不得发生任何 Redis 交互
        verify(redissonClient, never()).getLock(anyString());
        verify(stringRedisTemplate, never()).execute(stubScript, SECKILL_KEYS, String.valueOf(USER_ID));
    }

    @Test
    @DisplayName("商家置过期（status=3）→ 30000，即使 end_time 未到也不放行")
    void rejectsWhenVoucherMarkedExpired() {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeSeckillVoucher());
        when(voucherService.getById(VOUCHER_ID)).thenReturn(voucherWithStatus(3));

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorCode()).isEqualTo(ErrorCode.VOUCHER_NOT_FOUND.getCode());
        verify(redissonClient, never()).getLock(anyString());
        verify(stringRedisTemplate, never()).execute(stubScript, SECKILL_KEYS, String.valueOf(USER_ID));
    }

    // ---------------------------------------------------------------
    // 放行路径：status=1 且时间窗内，走完整 Lua 预扣 + Stream 投递
    // ---------------------------------------------------------------

    @Test
    @DisplayName("上架中（status=1）且在时间窗内 → 放行并返回处理中的订单 ID")
    void passesWhenVoucherOnShelfAndWithinWindow() throws Exception {
        when(seckillVoucherService.getById(VOUCHER_ID)).thenReturn(activeSeckillVoucher());
        when(voucherService.getById(VOUCHER_ID)).thenReturn(voucherWithStatus(1));

        RLock lock = mock(RLock.class);
        when(lock.tryLock(0, 3, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redissonClient.getLock(RedisConstants.LOCK_SECKILL_ORDER_KEY + USER_ID + ":" + VOUCHER_ID))
                .thenReturn(lock);

        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(stringRedisTemplate.hasKey(RedisConstants.SECKILL_STOCK_KEY + VOUCHER_ID))
                .thenReturn(Boolean.FALSE);
        // Lua 预扣返回 0（成功）
        when(stringRedisTemplate.execute(stubScript, SECKILL_KEYS, String.valueOf(USER_ID)))
                .thenReturn(0L);
        when(redisIdWorker.nextId(RedisConstants.ORDER_ID_PREFIX)).thenReturn(777001L);

        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        when(streamOperations.add(anyString(), any(Map.class))).thenReturn(RecordId.of("0-1"));

        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertThat(result.getSuccess()).isTrue();
        assertThat(((CreateVoucherOrderResultDTO) result.getData()).getOrderId()).isEqualTo(777001L);
        // 放行后确实走了 Lua 预扣与 Stream 投递
        verify(stringRedisTemplate).execute(stubScript, SECKILL_KEYS, String.valueOf(USER_ID));
        verify(streamOperations).add(eq(RedisConstants.SECKILL_STREAM_KEY), any(Map.class));
        verify(lock).unlock();
    }

    // ---------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------

    private SeckillVoucher activeSeckillVoucher() {
        SeckillVoucher sv = new SeckillVoucher();
        sv.setVoucherId(VOUCHER_ID);
        sv.setStock(10);
        sv.setBeginTime(LocalDateTime.now().minusMinutes(1));
        sv.setEndTime(LocalDateTime.now().plusHours(2));
        return sv;
    }

    private Voucher voucherWithStatus(int status) {
        Voucher voucher = new Voucher();
        voucher.setId(VOUCHER_ID);
        voucher.setType(1);
        voucher.setStatus(status);
        return voucher;
    }

    /**
     * 反射注入字段：先找声明类本身，找不到再沿父类向上。
     */
    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found on " + target.getClass());
    }
}
