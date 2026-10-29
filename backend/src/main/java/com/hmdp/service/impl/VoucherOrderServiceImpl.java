package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.CreateVoucherOrderResultDTO;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.MyVoucherItemDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherOrderDetailDTO;
import com.hmdp.dto.VoucherOrderMessage;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.OrderStatus;
import com.hmdp.utils.PayType;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SeckillStockCompensator;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IShopService shopService;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 秒杀库存补偿器（P1-3）：Redis 侧补偿（库存 +1 + 移除占位）后置到事务提交后执行，
     * 由 Lua 原子完成两笔写，并以订单维度幂等标记防重放重复补偿。
     */
    @Resource
    private SeckillStockCompensator seckillStockCompensator;

    /**
     * 自注入事务代理（A1 修复）。
     * 消费线程经 this 直接调用会绕过 Spring 代理，导致 createOrderByMessage 的
     * @Transactional 失效；@Lazy 自注入拿到代理，保证事务生效。不改变任何业务语义。
     */
    @Autowired
    @Lazy
    private IVoucherOrderService voucherOrderSelf;

    /**
     * 秒杀 Stream 死信 key（A4）：字段缺失的脏消息 ACK 后转存于此供人工排查。
     * 私有常量，不改动 RedisConstants；Stream 正常消息格式不变。
     */
    private static final String SECKILL_STREAM_DLQ_KEY = "stream:seckill:orders:dlq";

    /**
     * H2：陈旧 Pending 消息认领阈值。消费进程使用随机 consumerName，重启后旧 consumer 的
     * Pending 消息不会出现在新进程自己的 PEL 中，若无人认领将永久搁浅。仅认领空闲时长
     * ≥ 该阈值的消息，避免误抢其他实例正在处理中的消息。
     */
    private static final Duration SECKILL_STREAM_CLAIM_IDLE = Duration.ofSeconds(60);

    /**
     * H2：单轮 XPENDING 扫描上限。认领是幂等补救动作，未处理完的消息会在下一轮继续被扫描。
     */
    private static final long SECKILL_STREAM_CLAIM_SCAN_COUNT = 100L;

    private final AtomicInteger counter = new AtomicInteger(0);

    private final ExecutorService seckillConsumerExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors(),  // Use CPU cores
        r -> {
            Thread t = new Thread(r, "seckill-stream-consumer-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    );

    private String consumerName;

    /**
     * Lua 脚本（资源文件方式加载），实现：
     *   1. 检查库存 seckill:stock:{id}
     *   2. 检查用户占位 seckill:orders:{id}（SISMEMBER）
     *   3. 原子扣减 + SADD 占位
     * 返回：0=成功, -1=库存不足, -2=重复下单
     */
    private DefaultRedisScript<Long> seckillScript;

    @PostConstruct
    private void init() {
        seckillScript = new DefaultRedisScript<>();
        seckillScript.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("lua/seckill.lua")));
        seckillScript.setResultType(Long.class);

        initStreamGroup();
        consumerName = RedisConstants.SECKILL_STREAM_CONSUMER + UUID.randomUUID();
        seckillConsumerExecutor.submit(this::consumeSeckillOrders);
    }

    @PreDestroy
    private void destroy() {
        if (seckillConsumerExecutor != null && !seckillConsumerExecutor.isShutdown()) {
            seckillConsumerExecutor.shutdownNow();
        }
    }

    private void initStreamGroup() {
        try {
            Boolean hasKey = stringRedisTemplate.hasKey(RedisConstants.SECKILL_STREAM_KEY);
            if (Boolean.FALSE.equals(hasKey)) {
                stringRedisTemplate.opsForStream().add(
                        RedisConstants.SECKILL_STREAM_KEY,
                        Collections.singletonMap("init", "1"));
            }
            stringRedisTemplate.opsForStream().createGroup(
                    RedisConstants.SECKILL_STREAM_KEY,
                    RedisConstants.SECKILL_STREAM_GROUP);
        } catch (Exception ignored) {
        }
    }

    private void consumeSeckillOrders() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(RedisConstants.SECKILL_STREAM_GROUP, consumerName),
                        StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                        StreamOffset.create(RedisConstants.SECKILL_STREAM_KEY, ReadOffset.lastConsumed()));
                if (records == null || records.isEmpty()) {
                    handlePendingList();
                    continue;
                }
                for (MapRecord<String, Object, Object> record : records) {
                    handleStreamRecord(record);
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                    initStreamGroup();
                    continue;
                }
                if (e.getMessage() != null && (e.getMessage().contains("destroyed") || e.getMessage().contains("closed"))) {
                    log.warn("Redis 连接 Factory/Connection 已关闭，停止秒杀 Stream 消费线程");
                    break;
                }
                log.error("秒杀 Stream 消费异常", e);
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void handlePendingList() {
        // H2：先把「其他 consumer 遗留的陈旧 Pending」认领到本 consumer 名下，
        // 使其进入下面 while 循环的读取范围（ReadOffset.from("0") 只读本 consumer 的 PEL）。
        claimStaleMessages();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate.opsForStream().read(
                        Consumer.from(RedisConstants.SECKILL_STREAM_GROUP, consumerName),
                        StreamReadOptions.empty().count(10),
                        StreamOffset.create(RedisConstants.SECKILL_STREAM_KEY, ReadOffset.from("0")));
                if (records == null || records.isEmpty()) {
                    break;
                }
                boolean retryNeeded = false;
                for (MapRecord<String, Object, Object> record : records) {
                    try {
                        handleStreamRecord(record);
                    } catch (Exception e) {
                        // 单条失败不能中断整批：未 ACK 的消息会留在 PEL 头部，
                        // 若直接抛出，后面同批已投递的 Pending 会被无限期饿死。
                        // 这里记录后继续处理后续消息，本轮结束再退避一轮。
                        retryNeeded = true;
                        log.error("处理 pending 消息失败，保留待重放, recordId={}", record.getId(), e);
                    }
                }
                if (retryNeeded) {
                    try {
                        TimeUnit.SECONDS.sleep(1);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    break;
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (e.getMessage() != null && (e.getMessage().contains("destroyed") || e.getMessage().contains("closed"))) {
                    break;
                }
                log.error("处理 pending-list 异常", e);
                try {
                    TimeUnit.SECONDS.sleep(1);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                break;
            }
        }
    }

    /**
     * H2：认领「陈旧」Pending 消息（跨进程搁浅修复）。
     * <p>
     * 背景：consumerName 每次启动随机生成，{@link #handlePendingList()} 只读当前 consumer
     * 自己的 PEL。若进程在「XREADGROUP 已投递、业务未完成、ACK 未发出」之间退出，消息会滞留
     * 在旧 consumer 的 PEL 中，新进程永远不会读到 → 永久搁浅。
     * </p>
     * <p>
     * 处理：用 XPENDING 扫描组内全部 Pending（不限 consumer），挑出「非本 consumer 且空闲时长
     * ≥ {@link #SECKILL_STREAM_CLAIM_IDLE}」的消息，经 XCLAIM 过户到本 consumer。过户后消息
     * 进入本进程 PEL，由 {@link #handlePendingList()} 现有循环按 ReadOffset.from("0") 正常重放，
     * 不复制任何消息处理逻辑。
     * </p>
     * <p>
     * 安全边界：idle 阈值确保不会误抢其他实例「正在处理中」的消息；认领本身幂等，重复认领同一
     * recordId 只会刷新 owner，不产生额外业务效果（落库幂等由 H3 + DB CAS + UK 兜底）。本方法
     * 整体 try/catch，任何异常只告警不抛出，绝不影响既有 pending 处理链路。
     * </p>
     */
    private void claimStaleMessages() {
        try {
            PendingMessages pending = stringRedisTemplate.opsForStream().pending(
                    RedisConstants.SECKILL_STREAM_KEY,
                    RedisConstants.SECKILL_STREAM_GROUP,
                    Range.unbounded(),
                    SECKILL_STREAM_CLAIM_SCAN_COUNT);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            List<RecordId> staleIds = new ArrayList<>();
            for (PendingMessage pm : pending) {
                if (consumerName.equals(pm.getConsumerName())) {
                    continue; // 本 consumer 的 Pending 由 handlePendingList 正常重放，无需认领
                }
                if (pm.getElapsedTimeSinceLastDelivery().compareTo(SECKILL_STREAM_CLAIM_IDLE) < 0) {
                    continue; // 空闲不足阈值，可能仍在其他实例处理中，不抢
                }
                staleIds.add(pm.getId());
            }
            if (staleIds.isEmpty()) {
                return;
            }
            List<MapRecord<String, Object, Object>> claimed = stringRedisTemplate.opsForStream().claim(
                    RedisConstants.SECKILL_STREAM_KEY,
                    RedisConstants.SECKILL_STREAM_GROUP,
                    consumerName,
                    SECKILL_STREAM_CLAIM_IDLE,
                    staleIds.toArray(new RecordId[0]));
            log.info("H2 认领陈旧 Pending 消息 {} 条（组内扫描 {} 条）",
                    claimed == null ? 0 : claimed.size(), pending.size());
        } catch (Exception e) {
            log.warn("H2 认领陈旧 Pending 消息失败，下一轮重试", e);
        }
    }

    private void handleStreamRecord(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        // A4：脏消息隔离——字段缺失时直接转 DLQ 并 ACK，避免毒消息卡住整个消费线程。
        // 正常消息的解析/落库/ACK 顺序与之前完全一致。
        Object orderIdObj = fields.get("orderId");
        Object userIdObj = fields.get("userId");
        Object voucherIdObj = fields.get("voucherId");
        if (orderIdObj == null || userIdObj == null || voucherIdObj == null) {
            moveToDlqAndAck(record, fields, "字段缺失");
            return;
        }
        Long orderId;
        Long userId;
        Long voucherId;
        try {
            orderId = Long.valueOf(orderIdObj.toString());
            userId = Long.valueOf(userIdObj.toString());
            voucherId = Long.valueOf(voucherIdObj.toString());
        } catch (NumberFormatException e) {
            // H1：非数字字段同属毒消息，转 DLQ + ACK 终态，不进入无限重试。
            moveToDlqAndAck(record, fields, "字段非数字");
            return;
        }
        VoucherOrderMessage message = new VoucherOrderMessage(orderId, userId, voucherId);
        try {
            // A1：经自注入代理调用，createOrderByMessage 的事务生效。
            voucherOrderSelf.createOrderByMessage(message);
        } catch (DuplicateKeyException e) {
            // A3：UK 冲突——DB 扣减已随事务回滚，需判断是否需要补偿 Redis 预扣。
            // H2 加固：XCLAIM 允许同一条消息被两个 consumer 并发处理（A、B 同时认领同一 recordId）：
            //   情况A（同 orderId 重复消费）：B 撞 PK 时若 getById(orderId) != null，说明 A 已成功
            //       落库，Redis 预扣已被 A 兑现；B 的 DB 事务已整体回滚，绝不能再补偿 Redis，
            //       否则 R−D 出现 +1 永久漂移。此处仅跳过补偿，落到方法尾部统一 ACK。
            //   情况B（不同 orderId、同 userId+voucherId 冲突）：getById(orderId) == null，说明本消息
            //       自身未落库，是 DB 中已有其他订单占用同一 (userId, voucherId)；本消息的 Redis 预扣
            //       需要回滚，保留原补偿逻辑。
            if (getById(orderId) != null) {
                log.warn("Stream 撞唯一约束但本 orderId 已落库（并发认领重叠），跳过 Redis 补偿, "
                                + "orderId={}, userId={}, voucherId={}",
                        orderId, userId, voucherId);
            } else {
                log.warn("Stream 落库撞唯一约束，已回滚 DB，补偿 Redis 预扣, userId={}, voucherId={}",
                        userId, voucherId, e);
                String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
                stringRedisTemplate.opsForValue().increment(stockKey);
                String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
                stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());
            }
        }
        // H1：此处不再吞没未知异常。异常向上冒泡到 handlePendingList / consumeSeckillOrders
        // 既有的 catch，由那里的 sleep(1)+break 提供退避，且不 ACK，保留 Pending 重放语义
        // （与改动前 HEAD 行为一致）。原「宽 catch(Exception) → return」会让 pending 循环
        // 无退避地反复重读同一条消息，并阻塞新消息消费。
        stringRedisTemplate.opsForStream().acknowledge(
                RedisConstants.SECKILL_STREAM_KEY,
                RedisConstants.SECKILL_STREAM_GROUP,
                record.getId());
    }

    /**
     * H1：将不可解析 / 不可处理的脏消息转存 DLQ 并 ACK。
     * <p>
     * DLQ 写入失败时不 ACK，保留 Pending 重放语义（与 A4 原设计一致）。
     * 脏消息无法得知 stockKey / orderKey，故此处不做 Redis 预扣补偿。
     * </p>
     */
    private void moveToDlqAndAck(MapRecord<String, Object, Object> record,
                                 Map<Object, Object> fields,
                                 String reason) {
        log.error("秒杀 Stream 脏消息（{}），转 DLQ 后丢弃, recordId={}, fields={}",
                reason, record.getId(), fields);
        try {
            stringRedisTemplate.opsForList().rightPush(
                    SECKILL_STREAM_DLQ_KEY,
                    record.getId().getValue() + "|" + fields);
        } catch (Exception e) {
            log.warn("DLQ 写入失败，不 ACK，待 Pending 重放, recordId={}", record.getId(), e);
            return;
        }
        stringRedisTemplate.opsForStream().acknowledge(
                RedisConstants.SECKILL_STREAM_KEY,
                RedisConstants.SECKILL_STREAM_GROUP,
                record.getId());
    }

    /**
     * 秒杀优惠券下单（异步 Stream 模式）
     * 流程：
     * 1. 校验券主表上下架状态（{@code tb_voucher.status}）与秒杀时间窗口。
     * 2. 执行 Lua 脚本进行原子预检（校验并扣减 Redis 库存，校验一人一单）。
     * 3. 预检成功后，将订单消息写入 Redis Stream。
     * 4. 由后台消费者异步落库。
     * 5. 立即返回订单 ID（异步处理中）。
     *
     * @param voucherId 秒杀优惠券 ID
     * @return 包含订单 ID 的结果，状态为“处理中”
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 校验秒杀券时间窗口
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail(ErrorCode.VOUCHER_NOT_FOUND);
        }
        // 1.1 校验券主表上下架状态（P1-2）。秒杀券的售卖开关以 tb_voucher.status 为权威：
        //     商家下架（2）/ 置为过期（3）后必须立即停售，不能只依赖 tb_seckill_voucher
        //     的 end_time。普通购券链路（createVoucherOrder）本就校验 status，此处补齐
        //     秒杀链路的同一校验，两条链路口径一致；不存在/已下架统一按「券不存在」应答，
        //     不向调用方泄露「曾存在但已下架」的额外信息。
        Voucher voucherInfo = voucherService.getById(voucherId);
        if (voucherInfo == null
                || (voucherInfo.getStatus() != null && voucherInfo.getStatus() != 1)) {
            return Result.fail(ErrorCode.VOUCHER_NOT_FOUND);
        }
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(voucher.getBeginTime())) {
            return Result.fail(ErrorCode.SECKILL_NOT_STARTED);
        }
        if (now.isAfter(voucher.getEndTime())) {
            return Result.fail(ErrorCode.SECKILL_ENDED);
        }
        // 1.2 DB 库存预检（防止 Redis 库存与 DB 不一致时放行后无法落库）
        if (voucher.getStock() <= 0) {
            return Result.fail(ErrorCode.STOCK_NOT_ENOUGH);
        }

        Long userId = UserHolder.getUser().getId();

        // 1.5 DB 一人一单校验（防止 Redis 占位与 DB 不一致导致重复下单）
        // P1-4：只统计仍占名额的活跃订单；取消/退款终态已释放名额
        // （物理兜底：V17 的 uk_user_voucher_active 生成列唯一索引）。
        long orderCount = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .notIn("status", OrderStatus.UNIQUENESS_RELEASED_STATUSES)
                .count();
        if (orderCount > 0) {
            return Result.fail(ErrorCode.ORDER_DUPLICATE);
        }

        RLock lock = redissonClient.getLock(RedisConstants.LOCK_SECKILL_ORDER_KEY + userId + ":" + voucherId);
        boolean locked;
        try {
            locked = lock.tryLock(0, 3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail(ErrorCode.SYSTEM_ERROR);
        }
        if (!locked) {
            return Result.fail(ErrorCode.ORDER_CONFLICT);
        }
        try {
            // 2. 初始化/校正 Redis 库存（以 DB 为权威：key 不存在时冷启动写入，值高于 DB 剩余时校正）
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
            String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(stockKey))) {
                stringRedisTemplate.opsForValue().set(stockKey, voucher.getStock().toString());
                log.info("秒杀库存冷启动写入 Redis, voucherId={}, stock={}", voucherId, voucher.getStock());
            } else {
                String cachedStock = stringRedisTemplate.opsForValue().get(stockKey);
                if (cachedStock != null && Long.parseLong(cachedStock) > voucher.getStock()) {
                    stringRedisTemplate.opsForValue().set(stockKey, voucher.getStock().toString());
                    log.warn("秒杀库存 Redis 高于 DB 剩余，已校正, voucherId={}, redis={}→db={}",
                            voucherId, cachedStock, voucher.getStock());
                }
            }

            // 3. 执行 Lua 原子预检（库存扣减 + 用户占位）
            List<String> keys = Arrays.asList(stockKey, orderKey);
            Long result = stringRedisTemplate.execute(seckillScript, keys, userId.toString());
            if (result == null) {
                log.error("Lua 脚本执行异常, voucherId={}, userId={}", voucherId, userId);
                return Result.fail(ErrorCode.SYSTEM_ERROR);
            }

            int r = result.intValue();
            if (r == -1) {
                return Result.fail(ErrorCode.STOCK_NOT_ENOUGH);
            }
            if (r == -2) {
                return Result.fail(ErrorCode.ORDER_DUPLICATE);
            }

            // 4. Lua 已原子扣减成功，预分配订单 ID
            long orderId = redisIdWorker.nextId(RedisConstants.ORDER_ID_PREFIX);

            // 5. 写入 Redis Stream，异步落库
            // A2：XADD 失败时回滚 Redis 预扣（与消费侧落库失败的回滚语义一致），
            // 避免“库存已扣、占位已 mark、消息无”的泄漏。成功路径与之前完全一致。
            Map<String, String> fields = new HashMap<>();
            fields.put("orderId", String.valueOf(orderId));
            fields.put("userId", String.valueOf(userId));
            fields.put("voucherId", String.valueOf(voucherId));
            try {
                stringRedisTemplate.opsForStream().add(RedisConstants.SECKILL_STREAM_KEY, fields);
            } catch (Exception e) {
                log.error("秒杀 Stream 投递失败，回滚 Redis 预扣, voucherId={}, userId={}, orderId={}",
                        voucherId, userId, orderId, e);
                stringRedisTemplate.opsForValue().increment(stockKey);
                stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());
                return Result.fail(ErrorCode.SYSTEM_ERROR);
            }

            // 6. 立即返回（订单异步创建中）
            CreateVoucherOrderResultDTO dto = new CreateVoucherOrderResultDTO();
            dto.setOrderId(orderId);
            dto.setVoucherId(voucherId);
            dto.setOrderStatus(OrderStatus.PENDING_PAYMENT);
            dto.setDuplicateBlocked(false);
            return Result.ok(dto);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * Redis Stream 消费回调：异步创建订单并落库
     * 包含数据库幂等校验和 CAS 库存扣减。
     *
     * @param msg 订单消息，包含 orderId, userId, voucherId
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createOrderByMessage(VoucherOrderMessage msg) {
        Long userId    = msg.getUserId();
        Long voucherId = msg.getVoucherId();
        Long orderId   = msg.getOrderId();

        // H3 本消息幂等判定：以消息自带 orderId（预分配主键）判断「本消息是否已落库」。
        //   存在 → 当前消息已完成 DB 落库，属于 ACK 丢失后的重放：
        //          Redis 预扣已被本次落库兑现，禁止再次补偿，仅返回交由调用方 ACK。
        //   不存在 → 继续走后续业务兜底与落库（DB CAS + UK 仍是最终幂等机制）。
        if (getById(orderId) != null) {
            log.info("Stream 幂等命中：本消息已落库，跳过 Redis 补偿, orderId={}, userId={}, voucherId={}",
                    orderId, userId, voucherId);
            return;
        }

        // 1. DB 幂等校验（防止 Stream 消息重复消费导致重复落库）
        // P1-4：终态（取消/退款）订单不占名额——用户取消后重新抢购产生的订单消息
        // 不能被历史终态单拦截，否则库存/占位已回补却永远落不了库。
        long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .notIn("status", OrderStatus.UNIQUENESS_RELEASED_STATUSES)
                .count();
        if (count > 0) {
            log.warn("Stream 幂等拦截：订单已存在，跳过落库并回滚 Redis 预扣, userId={}, voucherId={}", userId, voucherId);
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
            stringRedisTemplate.opsForValue().increment(stockKey);
            String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
            stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());
            return;
        }

        // 2. DB 扣减库存（CAS：stock > 0 防止超卖）
        boolean deducted = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!deducted) {
            // DB 库存不足（可能已被其他订单扣完），回滚 Redis 预扣减。
            // 但先确认「本消息」确实没落库：XCLAIM 可能让两个 consumer 并发处理同一 recordId，
            // 对方已落库时这里再 +1 会造成 Redis 比 DB 永久多 1（P1）。
            if (getById(orderId) != null) {
                log.warn("DB 库存扣减失败但本 orderId 已落库（并发认领重叠），跳过 Redis 回补, "
                        + "orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
                return;
            }
            log.warn("DB 库存不足（Redis/DB 不一致或已售罄），回滚 Redis, voucherId={}", voucherId);
            String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
            stringRedisTemplate.opsForValue().increment(stockKey);
            String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
            stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());

            return;
        }

        // 3. 创建订单记录，使用预分配的 orderId
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(OrderStatus.PENDING_PAYMENT); // 1=待支付（领取成功，需在“我的优惠券”中完成支付）
        order.setPayType(PayType.BALANCE); // 1=余额支付
        save(order);
        log.info("秒杀订单落库成功, orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
    }

    /**
     * 普通优惠券购买（同步阻塞模式）
     * 使用分布式锁保障一人一单。
     *
     * @param voucherId 优惠券 ID
     * @return 购买结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result purchaseVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        RLock lock = redissonClient.getLock(RedisConstants.LOCK_ORDER_KEY + userId + ":" + voucherId);
        boolean locked;
        try {
            locked = lock.tryLock(0, 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail(ErrorCode.SYSTEM_ERROR);
        }
        if (!locked) {
            return Result.fail(ErrorCode.ORDER_CONFLICT);
        }
        try {
            // 获取代理对象以确保事务生效
            return ((IVoucherOrderService) AopContext.currentProxy()).createVoucherOrder(voucherId);
        } finally {
            releaseLockAfterTransaction(lock);
        }
    }

    /**
     * 把分布式锁的释放推迟到本次事务真正结束之后。
     *
     * <p>为什么不能直接在 {@code finally} 里 unlock：本方法与它内部经代理调用的
     * {@link #createVoucherOrder(Long)} 共用同一个事务（{@code @Transactional} 默认
     * {@code REQUIRED}），而 {@code finally} 是在方法返回时执行，此时事务**尚未提交**。
     * unlock 与 commit 之间存在窗口：第二个请求拿到锁后执行
     * {@code createVoucherOrder} 的一人一单校验 {@code count()}，读不到第一个请求
     * 尚未提交的 INSERT，于是继续走到 {@code save(order)}，撞上
     * {@code tb_voucher_order.uk_user_voucher} 唯一键并抛 {@code DuplicateKeyException}。
     * 数据完整性由唯一键兜住（不会产生重复订单），但用户拿到的是 99999「系统繁忙」
     * 而不是应有的 30002「您已购买过该优惠券」，同时服务端 ERROR 级刷栈。
     * 2026-10-06 审计实测：120 张券 × 12 并发命中 8 张券，480 请求中泄漏 2 个 99999。
     *
     * <p>修法：把 unlock 注册为事务同步回调，在 {@code afterCompletion}（commit 或
     * rollback 之后）才释放，这样第二个请求的 {@code count()} 一定能读到已提交的行。
     * 唯一键仍然保留，作为「锁租约超时 + 事务超长」等极端重叠场景的最终兜底。
     *
     * <p>若当前线程没有活动的事务同步（例如被绕过代理直接调用），退化为立即释放，
     * 行为与改动前一致。锁本身带 5s 租约（{@code tryLock(0, 5, SECONDS)}），
     * 即使回调因故未执行也会自动过期，不会永久占用。
     */
    private void releaseLockAfterTransaction(RLock lock) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    unlockQuietly(lock);
                }
            });
            return;
        }
        unlockQuietly(lock);
    }

    /**
     * 释放锁的统一出口。回调执行时若判定不再持有（例如事务耗时超过锁租约，
     * Redisson 已让锁过期），或 Redis 通信异常，都不应让调用方的主流程失败——
     * 租约过期本身已经保证了并发正确性。
     */
    private void unlockQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("释放购买分布式锁失败, lockName={}, 已等待租约过期兜底", lock.getName(), e);
        }
    }

    /**
     * 事务内创建订单（同步模式供复用）
     *
     * @param voucherId 优惠券 ID
     * @return 包含订单 ID 的结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        // 0. 券存在性 / 类型 / 上下架。
        //    同步购买只服务普通券；秒杀券必须走 seckillVoucher（Lua 预扣 + 时间窗 + Stream）。
        //    否则任何登录用户都能在活动开始前/结束后用本入口直接买走秒杀库存（P0）。
        Voucher voucher = voucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail(ErrorCode.VOUCHER_NOT_FOUND);
        }
        if ((voucher.getType() != null && voucher.getType() == 1)
                || seckillVoucherService.getById(voucherId) != null) {
            return Result.fail(ErrorCode.VOUCHER_TYPE_MISMATCH);
        }
        if (voucher.getStatus() != null && voucher.getStatus() != 1) {
            return Result.fail(ErrorCode.VOUCHER_NOT_FOUND);
        }

        // 1. 一人一单校验
        // P1-4：只统计活跃订单；取消/退款终态已释放名额（同 seckillVoucher 口径，
        // 物理兜底为 V17 的 uk_user_voucher_active 生成列唯一索引）。
        long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .notIn("status", OrderStatus.UNIQUENESS_RELEASED_STATUSES)
                .count();
        if (count > 0) {
            return Result.fail(ErrorCode.ORDER_DUPLICATE);
        }

        // 3. 创建并保存订单
        VoucherOrder order = new VoucherOrder();
        long orderId = redisIdWorker.nextId(RedisConstants.ORDER_ID_PREFIX);
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(OrderStatus.PENDING_PAYMENT); // 1=待支付（领取成功，需在“我的优惠券”中完成支付）
        order.setPayType(PayType.BALANCE); // 1=余额支付
        save(order);

        CreateVoucherOrderResultDTO dto = new CreateVoucherOrderResultDTO();
        dto.setOrderId(orderId);
        dto.setVoucherId(voucherId);
        dto.setOrderStatus(1);
        dto.setDuplicateBlocked(false);
        return Result.ok(dto);
    }

    /**
     * 查询订单当前支付状态
     *
     * @param orderId 订单 ID
     * @return 状态码：-1 表示不存在（排队中或失败），其他为数据库状态
     */
    @Override
    public Result getOrderStatus(Long orderId) {
        VoucherOrder order = getById(orderId);
        Long userId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        // 不存在或非本人订单：统一返回「不存在」，与订单写操作的越权口径一致，不泄露订单存在性
        if (order == null || userId == null || !userId.equals(order.getUserId())) {
            return Result.ok(OrderStatus.NOT_FOUND);
        }
        return Result.ok(order.getStatus());
    }

    @Override
    public Result queryMyVouchers(String tab, Integer current, Integer size) {
        Long userId = UserHolder.getUser().getId();
        List<Integer> statuses = resolveStatusesByTab(tab);
        if (statuses == null) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "无效的 tab 参数");
        }

        // 必须在分页查询之前收敛：否则已过期的待支付单会继续占用「未使用」的 total，
        // 出现「列表里没有、总数却对不上」的错位。
        settleExpiredPendingOrders(userId);

        Page<VoucherOrder> page = query()
                .eq("user_id", userId)
                .in("status", statuses)
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        List<VoucherOrder> orders = page.getRecords();
        if (orders == null || orders.isEmpty()) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }

        List<Long> voucherIds = orders.stream()
                .map(VoucherOrder::getVoucherId)
                .distinct()
                .collect(Collectors.toList());
        List<Voucher> vouchers = voucherService.query()
                .in("id", voucherIds)
                .list();
        Map<Long, Voucher> voucherMap = new HashMap<>(vouchers.size());
        for (Voucher voucher : vouchers) {
            voucherMap.put(voucher.getId(), voucher);
        }

        List<Long> shopIds = vouchers.stream()
                .map(Voucher::getShopId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, Shop> shopMap = new HashMap<>();
        if (!shopIds.isEmpty()) {
            for (Shop shop : shopService.listByIds(shopIds)) {
                shopMap.put(shop.getId(), shop);
            }
        }

        Map<Long, SeckillVoucher> seckillMap = new HashMap<>();
        List<Long> seckillIds = vouchers.stream()
                .filter(v -> v.getType() != null && v.getType() == 1)
                .map(Voucher::getId)
                .distinct()
                .collect(Collectors.toList());
        if (!seckillIds.isEmpty()) {
            for (SeckillVoucher sv : seckillVoucherService.listByIds(seckillIds)) {
                seckillMap.put(sv.getVoucherId(), sv);
            }
        }

        List<MyVoucherItemDTO> list = orders.stream().map(order -> {
            MyVoucherItemDTO dto = new MyVoucherItemDTO();
            dto.setOrderId(order.getId());
            dto.setOrderStatus(order.getStatus());
            dto.setOrderCreateTime(order.getCreateTime());
            dto.setPayTime(order.getPayTime());
            dto.setVoucherId(order.getVoucherId());

            Voucher voucher = voucherMap.get(order.getVoucherId());
            if (voucher != null) {
                dto.setTitle(voucher.getTitle());
                dto.setSubTitle(voucher.getSubTitle());
                dto.setRules(voucher.getRules());
                dto.setPayValue(voucher.getPayValue());
                dto.setActualValue(voucher.getActualValue());
                dto.setType(voucher.getType());
                dto.setShopId(voucher.getShopId());
                Shop shop = shopMap.get(voucher.getShopId());
                if (shop != null) {
                    dto.setShopName(shop.getName());
                    dto.setShopImage(shop.getImages());
                }
                SeckillVoucher sv = seckillMap.get(voucher.getId());
                if (sv != null) {
                    dto.setBeginTime(sv.getBeginTime());
                    dto.setEndTime(sv.getEndTime());
                }
            }
            return dto;
        }).collect(Collectors.toList());

        return Result.ok(list, page.getTotal());
    }

    @Override
    public Result queryOrderDetail(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            // 与 redeem 接口同口径：订单不存在 → 200 + 30008（10008 是「参数校验失败」，语义错误）
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!userId.equals(order.getUserId())) {
            // 与 pay/cancel/refund/delete 同口径：他人订单一律「订单不存在」，
            // 不泄露订单存在性，也不用 10008（参数校验失败）冒充鉴权失败（P2）。
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }

        Voucher voucher = voucherService.getById(order.getVoucherId());
        VoucherOrderDetailDTO dto = new VoucherOrderDetailDTO();
        dto.setOrderId(order.getId());
        dto.setUserId(order.getUserId());
        dto.setVoucherId(order.getVoucherId());
        dto.setStatus(order.getStatus());
        dto.setPayType(order.getPayType());
        dto.setCreateTime(order.getCreateTime());
        dto.setPayTime(order.getPayTime());
        dto.setUseTime(order.getUseTime());
        dto.setRefundTime(order.getRefundTime());

        if (voucher != null) {
            dto.setVoucherTitle(voucher.getTitle());
            dto.setVoucherSubTitle(voucher.getSubTitle());
            dto.setVoucherRules(voucher.getRules());
            dto.setPayValue(voucher.getPayValue());
            dto.setActualValue(voucher.getActualValue());
            dto.setShopId(voucher.getShopId());
            Shop shop = voucher.getShopId() == null ? null : shopService.getById(voucher.getShopId());
            if (shop != null) {
                dto.setShopName(shop.getName());
                dto.setShopImage(shop.getImages());
            }
            SeckillVoucher sv = seckillVoucherService.getById(voucher.getId());
            if (sv != null) {
                dto.setBeginTime(sv.getBeginTime());
                dto.setEndTime(sv.getEndTime());
            }
        }
        return Result.ok(dto);
    }

    @Override
    public Result payVoucherOrder(Long orderId, Integer payType) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return Result.fail(ErrorCode.ORDER_STATUS_INVALID);
        }
        if (payType == null || payType < PayType.BALANCE || payType > PayType.ALIPAY) {
            return Result.fail(ErrorCode.PARAM_INVALID);
        }

        // 秒杀券过期校验：超过有效期禁止支付。
        // 同时把订单收敛到终态 EXPIRED —— 否则该单会永远卡在 PENDING_PAYMENT，
        // 既付不了、也混在「未使用」里与真正可用的券无法区分，用户看不到也清理不掉。
        // CAS 条件与下面 pay/cancel/refund 三处保持一致。
        // 库存不回补：券已过期，回补到一张过期券上无人能用，反而会让后续抢券
        // 再生成一批同样卡死的订单。
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(order.getVoucherId());
        if (seckillVoucher != null && seckillVoucher.getEndTime() != null
                && LocalDateTime.now().isAfter(seckillVoucher.getEndTime())) {
            update()
                    .set("status", OrderStatus.EXPIRED)
                    .eq("id", orderId)
                    .eq("status", OrderStatus.PENDING_PAYMENT)
                    .update();
            return Result.fail(ErrorCode.VOUCHER_EXPIRED);
        }

        boolean updated = update()
                .set("status", OrderStatus.PAID)
                .set("pay_type", payType)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", OrderStatus.PENDING_PAYMENT)
                .update();
        if (!updated) {
            return Result.fail(ErrorCode.ORDER_STATUS_INVALID);
        }
        return Result.ok();
    }

    // P1-3：DB 状态 UPDATE 与 DB 库存回补仍在同一事务（原子性不变）；Redis 补偿
    // 后置到 afterCommit，由 SeckillStockCompensator 以 Lua 原子执行并带幂等标记。
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result cancelVoucherOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return Result.fail(ErrorCode.ORDER_STATUS_INVALID);
        }

        boolean updated = update()
                .set("status", OrderStatus.CANCELLED)
                .eq("id", orderId)
                .eq("status", OrderStatus.PENDING_PAYMENT)
                .update();
        if (!updated) {
            return Result.fail(ErrorCode.ORDER_STATUS_INVALID);
        }
        rollbackStock(order.getId(), order.getVoucherId(), userId);
        return Result.ok();
    }

    // P1-3：同 cancel，DB 两写同事务；Redis 补偿后置到 afterCommit。
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result refundVoucherOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() != OrderStatus.PAID) {
            return Result.fail(ErrorCode.ORDER_STATUS_INVALID);
        }

        boolean updated = update()
                .set("status", OrderStatus.REFUNDED)
                .set("refund_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", OrderStatus.PAID)
                .update();
        if (!updated) {
            return Result.fail(ErrorCode.ORDER_STATUS_INVALID);
        }
        rollbackStock(order.getId(), order.getVoucherId(), userId);
        return Result.ok();
    }

    @Override
    public Result deleteVoucherOrder(Long orderId) {
        Long userId = UserHolder.getUser().getId();
        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail(ErrorCode.ORDER_NOT_FOUND);
        }
        // 允许删除的终态：已取消 / 已过期 / 已退款。
        // 5 EXPIRED 与 4/6 同性质（券已失效、永不会再流转），若不放行则该单永远删不掉。
        if (order.getStatus() != OrderStatus.CANCELLED
                && order.getStatus() != OrderStatus.EXPIRED
                && order.getStatus() != OrderStatus.REFUNDED) {
            return Result.fail(ErrorCode.ORDER_STATUS_INVALID);
        }

        boolean removed = baseMapper.physicallyDelete(orderId, userId) > 0;
        if (!removed) {
            return Result.fail(ErrorCode.ORDER_CREATE_FAILED);
        }
        // 清理秒杀 Redis 占位，避免删除后无法再次抢购
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + order.getVoucherId();
        stringRedisTemplate.opsForSet().remove(orderKey, userId.toString());
        return Result.ok();
    }

    /**
     * 回补库存与秒杀占位（取消/退款后调用，保证用户可再次抢购）
     * <p>
     * P1-3：DB 库存回补留在本事务内（与状态 UPDATE 原子）；Redis 侧补偿
     * （库存 +1 + 移除占位）后置到事务提交后，由 {@link SeckillStockCompensator}
     * 以一段 Lua 原子完成，并按 orderId 幂等。旧实现把两次 Redis 命令直接放在
     * 事务内：DB 回滚后 Redis 已 +1（虚高）、DB 提交但 Redis 失败（虚低）、
     * INCR 与 SREM 之间失败（占位残留）三种漂移都没有补偿路径。
     * </p>
     *
     * @param orderId   订单 ID（Redis 补偿幂等标记维度）
     * @param voucherId 券 ID
     * @param userId    用户 ID
     */
    private void rollbackStock(Long orderId, Long voucherId, Long userId) {
        // 只有秒杀单在创建时预扣过 DB/Redis 库存；普通券下单不扣库存，
        // 取消/退款若一律回补，会给 tb_seckill_voucher 凭空 +1（无行时空操作，
        // 但语义错误且掩盖数据异常）。按券的来源显式区分。
        if (seckillVoucherService.getById(voucherId) == null) {
            return;
        }
        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", voucherId)
                .update();
        // Redis 补偿后置：事务提交后才执行；提交失败（回滚）则回调不触发，
        // 绝不会出现「DB 没回补、Redis 先加了」的漂移。
        seckillStockCompensator.compensateAfterCommit(voucherId, userId, orderId);
    }

    /**
     * 读时惰性收敛：把当前用户「已过期却仍卡在 PENDING_PAYMENT」的订单结算为 EXPIRED。
     * <p>
     * 只覆盖从不去点支付的用户 —— 那种单子不会被 {@code payVoucherOrder} 的过期分支命中，
     * 会永远留在「未使用」里，用户既看不出它已失效、也删不掉（删除要求终态）。
     * <p>
     * 不引入定时任务：{@code @EnableScheduling} 属于新基础设施，且后台线程会与已冻结的
     * 秒杀 Stream 消费组争抢资源；这里按需触发、无新线程、幂等，稳态下多一次走索引的空查。
     * <p>
     * 库存不回补，理由同 {@code payVoucherOrder}：券已过期，回补到一张过期券上无人能用，
     * 反而会让后续抢券再生成一批同样卡死的订单。
     */
    private void settleExpiredPendingOrders(Long userId) {
        List<VoucherOrder> pending = query()
                .eq("user_id", userId)
                .eq("status", OrderStatus.PENDING_PAYMENT)
                .list();
        if (pending == null || pending.isEmpty()) {
            return;
        }
        Map<Long, SeckillVoucher> seckillMap = seckillVoucherService
                .listByIds(pending.stream()
                        .map(VoucherOrder::getVoucherId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList()))
                .stream()
                .collect(Collectors.toMap(SeckillVoucher::getVoucherId, sv -> sv, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        List<Long> expiredIds = new ArrayList<>();
        for (VoucherOrder order : pending) {
            SeckillVoucher sv = seckillMap.get(order.getVoucherId());
            if (sv != null && sv.getEndTime() != null && now.isAfter(sv.getEndTime())) {
                expiredIds.add(order.getId());
            }
        }
        if (expiredIds.isEmpty()) {
            return;
        }
        // 带 status 条件，避免与并发的支付/取消互相覆盖
        update()
                .set("status", OrderStatus.EXPIRED)
                .in("id", expiredIds)
                .eq("status", OrderStatus.PENDING_PAYMENT)
                .update();
    }

    private List<Integer> resolveStatusesByTab(String tab) {
        if (tab == null) {
            return null;
        }
        switch (tab) {
            case "unused":
                return Arrays.asList(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID);
            case "used":
                return Collections.singletonList(OrderStatus.USED);
            case "expired":
                return Collections.singletonList(OrderStatus.EXPIRED);
            case "invalid":
                return Arrays.asList(OrderStatus.CANCELLED, OrderStatus.REFUNDED);
            default:
                return null;
        }
    }
}
