package com.hmdp.mapper;

import com.hmdp.entity.ShopComment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 商铺评价 Mapper 接口
 * <p>
 * 评分汇总直接由 tb_shop_comments 实时聚合，不写回 tb_shop，
 * 从而避免触碰店铺缓存（cache:shop:*）。
 * </p>
 */
public interface ShopCommentMapper extends BaseMapper<ShopComment> {

    /**
     * 统计某店铺的评价总数
     */
    @Select("SELECT COUNT(*) FROM tb_shop_comments WHERE shop_id = #{shopId}")
    long countByShop(@Param("shopId") Long shopId);

    /**
     * 计算某店铺的平均评分（无评价时返回 null）
     */
    @Select("SELECT AVG(rating) FROM tb_shop_comments WHERE shop_id = #{shopId}")
    Double avgRatingByShop(@Param("shopId") Long shopId);
}
