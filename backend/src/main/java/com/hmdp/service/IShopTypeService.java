package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 商铺类型服务接口
 * <p>提供商铺分类信息的查询能力。</p>
 */
public interface IShopTypeService extends IService<ShopType> {

    /**
     * 查询所有商铺类型列表
     *
     * @return 商铺类型列表结果
     */
    Result queryTypeList();
}
