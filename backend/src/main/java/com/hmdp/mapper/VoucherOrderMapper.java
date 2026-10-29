package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

/**
 * 优惠券订单 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，提供对 tb_voucher_order 表的 CRUD 操作。
 * 自定义 SQL 方法请在对应的 XML 文件中定义。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /**
     * 物理删除订单记录（绕过逻辑删除，释放一人一单活跃名额，允许删除后重新购买）。
     * P1-4 起名额由 V17 的 uk_user_voucher_active 唯一索引约束（终态不占名额），
     * 删除仍是「订单记录也不再保留」的显式清理动作。
     */
    @Delete("DELETE FROM tb_voucher_order WHERE id = #{orderId} AND user_id = #{userId}")
    int physicallyDelete(@Param("orderId") Long orderId, @Param("userId") Long userId);

}
