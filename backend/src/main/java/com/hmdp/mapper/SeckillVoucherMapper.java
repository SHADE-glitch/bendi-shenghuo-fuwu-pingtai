package com.hmdp.mapper;

import com.hmdp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 秒杀优惠券 Mapper 接口
 * <p>
 * 继承 MyBatis-Plus 的 BaseMapper，提供对秒杀优惠券表的 CRUD 操作。
 * 该表与优惠券表(tb_voucher)为一对一关系，记录秒杀活动的库存、起止时间等核心信息。
 * 自定义 SQL 方法请在对应的 XML 文件中定义。
 * </p>
 *
 * @author 虎哥
 * @since 2022-01-04
 */
public interface SeckillVoucherMapper extends BaseMapper<SeckillVoucher> {

}
