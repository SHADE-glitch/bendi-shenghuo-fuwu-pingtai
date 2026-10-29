package com.hmdp.service.impl;

import com.hmdp.entity.SeckillVoucher;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 秒杀优惠券服务实现类
 * <p>
 * 秒杀优惠券数据访问层实现，与优惠券表一对一关联。
 * 当前继承 MyBatis-Plus ServiceImpl 提供基础 CRUD 能力，秒杀核心逻辑（库存扣减、分布式锁等）在订单层处理。
 * </p>
 */
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {

}
