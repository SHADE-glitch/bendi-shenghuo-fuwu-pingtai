package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.Voucher;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 优惠券 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，提供对 tb_voucher 表的 CRUD 操作。
 * 包含自定义 SQL 方法用于查询商铺可用优惠券列表。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherMapper extends BaseMapper<Voucher> {

    /**
     * 根据商铺 ID 查询可用的优惠券列表（含秒杀券逻辑）
     *
     * @param shopId 商铺 ID
     * @return 优惠券实体列表
     */
    List<Voucher> queryVoucherOfShop(@Param("shopId") Long shopId);
}
