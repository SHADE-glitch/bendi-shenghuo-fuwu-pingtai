package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.VoucherSaveRequest;
import com.hmdp.dto.VoucherUpdateRequest;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Shop;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 优惠券服务实现类
 * <p>
 * 提供商铺优惠券查询和秒杀优惠券创建功能。
 * 创建秒杀优惠券时同步写入数据库和 Redis 库存，保证事务一致性。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IShopService shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    public Result<Long> addVoucher(VoucherSaveRequest request) {
        // 类型契约：普通券入口只接受 type=0。
        // type=1 属于秒杀券，必须走 addSeckillVoucher；否则会写入
        // 「tb_voucher.type=1 但没有 tb_seckill_voucher 行、也没有 Redis 秒杀库存」
        // 的结构不完整数据。此校验在 save() 之前，保证不产生任何数据库写入。
        if (request.getType() == null || request.getType() != 0) {
            return Result.fail(ErrorCode.VOUCHER_TYPE_MISMATCH);
        }
        Voucher voucher = new Voucher();
        BeanUtil.copyProperties(request, voucher);
        save(voucher);
        return Result.ok(voucher.getId());
    }

    @Override
    @Transactional
    public Result<Long> addSeckillVoucher(VoucherSaveRequest request) {
        // 秒杀券的结构契约：type=1 + 正库存 + 合法时间窗。
        // 缺任何一项都会写出「有券无秒杀行」「库存为 0/负」「endTime 早于 beginTime」的坏数据，
        // 活动开始后 Lua 直接判库存不足或时间窗永远不成立。
        if (request.getType() == null || request.getType() != 1) {
            return Result.fail(ErrorCode.VOUCHER_TYPE_MISMATCH);
        }
        if (request.getStock() == null || request.getStock() <= 0) {
            return Result.fail(ErrorCode.STOCK_NOT_ENOUGH);
        }
        if (request.getBeginTime() == null || request.getEndTime() == null
                || !request.getBeginTime().isBefore(request.getEndTime())) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "秒杀开始时间必须早于结束时间");
        }
        Voucher voucher = new Voucher();
        BeanUtil.copyProperties(request, voucher);
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
        // 秒杀库存写到 Redis 必须等事务提交：若在事务内写、随后回滚，
        // 会留下指向不存在券的库存 key，Lua 仍会照常扣减并放行，产生无法落库的订单。
        registerAfterCommit(() -> stringRedisTemplate.opsForValue()
                .set(SECKILL_STOCK_KEY + voucher.getId(), voucher.getStock().toString()));
        return Result.ok(voucher.getId());
    }

    /** 事务提交后执行；无事务上下文（如单测直调）时立即执行。 */
    private void registerAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    @Override
    public Result<List<Voucher>> queryVouchersByOwner(Long ownerId) {
        // 防御性：归属条件为空时不得退化为「查询全部券」
        if (ownerId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        // 第一步：当前商家名下的店铺 id（owner_id 命中 idx_shop_owner 索引）
        List<Long> shopIds = shopService.query()
                .eq("owner_id", ownerId)
                .list()
                .stream()
                .map(Shop::getId)
                .collect(Collectors.toList());
        // 无店铺时直接返回空列表，避免生成非法的 IN () 条件
        if (shopIds.isEmpty()) {
            return Result.ok(new ArrayList<>());
        }
        // 第二步：这些店铺下的全部券。
        // 刻意不过滤 status —— 商家需要看到自己已下架/过期的券（消费者接口 queryVoucherOfShop 才过滤 status=1）
        List<Voucher> vouchers = query()
                .in("shop_id", shopIds)
                .orderByDesc("id")
                .list();
        fillSeckillInfo(vouchers);
        return Result.ok(vouchers);
    }

    @Override
    public Result<Void> updateOwnVoucher(Long voucherId, VoucherUpdateRequest request) {
        // 状态只允许数据库注释定义的三种取值：1 上架 / 2 下架 / 3 过期
        Integer status = request.getStatus();
        if (status != null && (status < 1 || status > 3)) {
            return Result.fail(ErrorCode.PARAM_INVALID);
        }
        // 白名单更新：只写入 VoucherUpdateRequest 声明的字段。
        // 不构造 shopId / type / stock / beginTime / endTime / createTime —— 归属、类型与秒杀状态不受请求体影响。
        // （title 由 @NotBlank 保证非空，因此 updateById 的 SET 子句至少含一个字段）
        Voucher update = new Voucher();
        update.setId(voucherId);
        update.setTitle(request.getTitle());
        update.setSubTitle(request.getSubTitle());
        update.setRules(request.getRules());
        update.setPayValue(request.getPayValue());
        update.setActualValue(request.getActualValue());
        update.setStatus(status);
        updateById(update);
        return Result.ok();
    }

    /**
     * 为秒杀券补充库存与起止时间。
     * <p>
     * {@code Voucher} 上的 {@code stock}/{@code beginTime}/{@code endTime} 是
     * {@code @TableField(exist = false)} 的非持久化字段，真实值在
     * {@code tb_seckill_voucher}；此处按券 id 批量回填，使商家管理视图与
     * 消费者接口 {@code queryVoucherOfShop} 的返回结构保持一致。
     * 仅填充返回对象，<b>不产生任何写操作</b>。
     * </p>
     */
    private void fillSeckillInfo(List<Voucher> vouchers) {
        List<Long> seckillIds = vouchers.stream()
                .filter(v -> v.getType() != null && v.getType() == 1)
                .map(Voucher::getId)
                .collect(Collectors.toList());
        if (seckillIds.isEmpty()) {
            return;
        }
        Map<Long, SeckillVoucher> seckillMap = new HashMap<>();
        for (SeckillVoucher sv : seckillVoucherService.listByIds(seckillIds)) {
            seckillMap.put(sv.getVoucherId(), sv);
        }
        for (Voucher voucher : vouchers) {
            SeckillVoucher sv = seckillMap.get(voucher.getId());
            if (sv != null) {
                voucher.setStock(sv.getStock());
                voucher.setBeginTime(sv.getBeginTime());
                voucher.setEndTime(sv.getEndTime());
            }
        }
    }

}
