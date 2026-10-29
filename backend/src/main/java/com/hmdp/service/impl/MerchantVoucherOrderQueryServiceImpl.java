package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.MerchantVoucherOrderItemDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IMerchantVoucherOrderQueryService;
import com.hmdp.service.IShopService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商家订单查询服务实现（Phase 3.4-B.2）
 * <p>
 * 实现 {@link IMerchantVoucherOrderQueryService}：分页返回<b>当前商家名下店铺</b>
 * 产生的优惠券订单。查询范围严格由 {@code merchantId} 推导，
 * 不接收任何客户端归属参数。
 * </p>
 *
 * <h3>查询链路（数据权限边界）</h3>
 * <pre>
 * merchantId
 *   → tb_shop.owner_id = merchantId        （步骤 1，命中 idx_shop_owner）
 *   → shopIds
 *   → tb_voucher.shop_id IN (shopIds)      （步骤 2，命中 fk_voucher_shop）
 *   → voucherIds
 *   → tb_voucher_order.voucher_id IN (voucherIds)   （步骤 3，命中 fk_order_voucher）
 *   → 分页（create_time DESC，可选 status）
 * </pre>
 * <p>
 * <b>为什么不能直接用订单判断归属</b>：{@code tb_voucher_order} 上<b>没有</b>
 * {@code shop_id} / {@code owner_id} 列，也没有 {@code user_id} 之外的归属信息；
 * 因此必须沿 {@code order.voucher_id → voucher.shop_id → shop.owner_id} 建立边界。
 * </p>
 *
 * <h3>查询次数（无 N+1）</h3>
 * <p>
 * 固定 <b>3 次查询</b>（店铺、券、订单分页），与当页记录数无关：
 * </p>
 * <ol>
 *   <li>店铺：一次 {@code query().eq("owner_id", ...)}；</li>
 *   <li>券：一次 {@code query().in("shop_id", shopIds)}；</li>
 *   <li>订单：一次 {@code query().in("voucher_id", ...).page(...)}（分页 + count）。</li>
 * </ol>
 * <p>
 * 组装阶段所需的 {@code Voucher} / {@code Shop} 均已在步骤 1、2 一次性载入内存并构建为
 * {@code Map}，<b>不再逐条查询</b>（即不存在「for order → select voucher/shop」的 N+1）。
 * </p>
 *
 * <h3>复用而非新增基础设施</h3>
 * <p>
 * 全程复用既有 {@code IService} 链式查询能力（与 {@code VoucherServiceImpl.queryVouchersByOwner}
 * 的两步 {@code IN} 模式一致），<b>未新增 Mapper 方法 / XML / 索引 / 缓存 / 线程池</b>。
 * 逻辑删除（{@code is_deleted = 0}）由 {@code @TableLogic} 全局配置自动追加，无需手写条件。
 * </p>
 */
@Slf4j
@Service
public class MerchantVoucherOrderQueryServiceImpl implements IMerchantVoucherOrderQueryService {

    @Resource
    private IShopService shopService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * {@inheritDoc}
     */
    @Override
    public Result<List<MerchantVoucherOrderItemDTO>> queryMerchantOrders(Long merchantId,
                                                                        Integer current,
                                                                        Integer size,
                                                                        Integer status) {
        // ------------------------------------------------------------------
        // 步骤 1：当前商家名下的店铺（owner_id 命中 idx_shop_owner）
        // ------------------------------------------------------------------
        List<Shop> shops = shopService.query()
                .eq("owner_id", merchantId)
                .list();
        if (shops.isEmpty()) {
            // 无店铺：直接返回空分页，避免构造非法的 IN ()
            return pageResult(new ArrayList<>(), 0L);
        }
        List<Long> shopIds = new ArrayList<>(shops.size());
        Map<Long, Shop> shopMap = new HashMap<>(shops.size());
        for (Shop shop : shops) {
            shopIds.add(shop.getId());
            shopMap.put(shop.getId(), shop);
        }

        // ------------------------------------------------------------------
        // 步骤 2：这些店铺下的全部券（shop_id 命中 fk_voucher_shop）
        // ------------------------------------------------------------------
        List<Voucher> vouchers = voucherService.query()
                .in("shop_id", shopIds)
                .list();
        if (vouchers.isEmpty()) {
            // 有店铺但无券：同样直接返回空分页
            return pageResult(new ArrayList<>(), 0L);
        }
        List<Long> voucherIds = new ArrayList<>(vouchers.size());
        Map<Long, Voucher> voucherMap = new HashMap<>(vouchers.size());
        for (Voucher voucher : vouchers) {
            voucherIds.add(voucher.getId());
            voucherMap.put(voucher.getId(), voucher);
        }

        // ------------------------------------------------------------------
        // 步骤 3：分页查询订单
        //   - voucher_id IN (voucherIds) 限定归属范围（命中 fk_order_voucher）
        //   - status 非空时追加过滤；为空则不过滤（查询全部状态）
        //   - create_time DESC 排序（命中 idx_order_create_time）
        //   - is_deleted = 0 由 @TableLogic 自动追加
        // ------------------------------------------------------------------
        Page<VoucherOrder> page = voucherOrderService.query()
                .in("voucher_id", voucherIds)
                .eq(status != null, "status", status)
                .orderByDesc("create_time")
                .page(new Page<>(current, size));

        List<VoucherOrder> orders = page.getRecords();
        if (orders == null || orders.isEmpty()) {
            return pageResult(new ArrayList<>(), page.getTotal());
        }

        // ------------------------------------------------------------------
        // 步骤 4：内存组装（voucher / shop 已在步骤 1、2 载入，无额外查询）
        // ------------------------------------------------------------------
        List<MerchantVoucherOrderItemDTO> list = new ArrayList<>(orders.size());
        for (VoucherOrder order : orders) {
            MerchantVoucherOrderItemDTO dto = new MerchantVoucherOrderItemDTO();
            // 订单维度
            dto.setOrderId(order.getId());
            dto.setStatus(order.getStatus());
            dto.setCreateTime(order.getCreateTime());
            dto.setPayTime(order.getPayTime());
            dto.setUseTime(order.getUseTime());
            dto.setUserId(order.getUserId());
            dto.setVoucherId(order.getVoucherId());

            // 券维度 + 店铺维度
            Voucher voucher = voucherMap.get(order.getVoucherId());
            if (voucher != null) {
                dto.setVoucherTitle(voucher.getTitle());
                dto.setPayValue(voucher.getPayValue());
                dto.setActualValue(voucher.getActualValue());
                dto.setShopId(voucher.getShopId());
                if (voucher.getShopId() != null) {
                    Shop shop = shopMap.get(voucher.getShopId());
                    if (shop != null) {
                        dto.setShopName(shop.getName());
                    }
                }
            }
            list.add(dto);
        }
        return pageResult(list, page.getTotal());
    }

    /**
     * 构造带 {@code total} 的分页响应。
     * <p>
     * 说明：{@code Result.ok(List<T> data, Long total)} 的泛型参数是<b>元素类型</b>
     * （返回 {@code Result<T>}），无法直接赋给本接口声明的 {@code Result<List<T>>}。
     * 因此这里先以 {@code Result.ok(list)}（{@code T = List<...>}）构造，再补 {@code total}，
     * 避免原始类型与未检查强转。
     * </p>
     */
    private Result<List<MerchantVoucherOrderItemDTO>> pageResult(List<MerchantVoucherOrderItemDTO> list,
                                                                 long total) {
        Result<List<MerchantVoucherOrderItemDTO>> result = Result.ok(list);
        result.setTotal(total);
        return result;
    }
}
