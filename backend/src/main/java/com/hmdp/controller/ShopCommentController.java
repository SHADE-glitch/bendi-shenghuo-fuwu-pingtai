package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopCommentCreateRequest;
import com.hmdp.service.IShopCommentService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商铺评价控制器
 * <p>
 * 读取接口挂在 {@code /shops/{shopId}/comments} 下，命中 MvcConfig 中
 * {@code /shops/**} 的放行规则，游客可浏览；写入接口挂在 {@code /shop-comments}
 * 下，需登录。
 * </p>
 */
@Validated
@RestController
public class ShopCommentController {

    @Resource
    private IShopCommentService shopCommentService;

    /**
     * 分页查询某店铺的评价（公开）
     */
    @GetMapping("/shops/{shopId}/comments")
    public Result queryByShop(
            @PathVariable("shopId") Long shopId,
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        return shopCommentService.queryByShop(shopId, current, size);
    }

    /**
     * 查询某店铺的评价汇总：数量 + 平均分（公开）
     */
    @GetMapping("/shops/{shopId}/comments/summary")
    public Result summary(@PathVariable("shopId") Long shopId) {
        return shopCommentService.summary(shopId);
    }

    /**
     * 发布评价
     */
    @PostMapping("/shop-comments")
    public Result create(@Valid @RequestBody ShopCommentCreateRequest request) {
        return shopCommentService.create(request);
    }

    /**
     * 分页查询我的评价
     */
    @GetMapping("/shop-comments/mine")
    public Result queryMine(
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        return shopCommentService.queryMine(current, size);
    }

    /**
     * 删除我的评价
     */
    @DeleteMapping("/shop-comments/{commentId}")
    public Result delete(@PathVariable("commentId") Long commentId) {
        return shopCommentService.delete(commentId);
    }
}
