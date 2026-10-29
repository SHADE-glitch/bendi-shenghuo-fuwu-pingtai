package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopCommentCreateRequest;
import com.hmdp.dto.ShopCommentUpdateRequest;
import com.hmdp.entity.ShopComment;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 商铺评价服务接口
 */
public interface IShopCommentService extends IService<ShopComment> {

    /**
     * 发布评价
     */
    Result create(ShopCommentCreateRequest request);

    /**
     * 分页查询某店铺的评价（公开）
     */
    Result queryByShop(Long shopId, Integer current, Integer size);

    /**
     * 分页查询当前用户的评价
     */
    Result queryMine(Integer current, Integer size);

    /**
     * 查询某店铺的评价汇总（数量 + 平均分）
     */
    Result summary(Long shopId);

    /**
     * 删除本人的评价
     */
    Result delete(Long commentId);

    /**
     * 编辑本人的评价（仅作者本人，越权按「评价不存在」应答，不转移所属店铺）
     *
     * @param commentId 评价id
     * @param request   内容与评分
     * @return 更新结果
     */
    Result updateComment(Long commentId, ShopCommentUpdateRequest request);
}
