package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 商户类型控制器
 * <p>
 * 提供商户分类列表查询的 REST API 接口，
 * 返回按排序字段升序排列的商户类型数据。
 * </p>
 */
@RestController
@RequestMapping("/shop-types")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    /**
     * 查询店铺类型列表
     *
     * @return 包含店铺类型列表的成功结果
     */
    @GetMapping
    public Result<List<ShopType>> queryTypeList() {
        return typeService.queryTypeList();
    }
}