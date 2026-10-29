package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.ShopCommentUpdateRequest;
import com.hmdp.service.IShopCommentService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商铺评价管理控制器
 * <p>补「评价可编辑」缺口，仅作者本人可修改，不改评价所属店铺。</p>
 */
@RestController
public class ShopCommentManageController {

    @Resource
    private IShopCommentService shopCommentService;

    /**
     * 编辑我的评价（仅作者本人）
     */
    @PutMapping("/shop-comments/{commentId}")
    public Result updateComment(@PathVariable("commentId") Long commentId,
                                @Valid @RequestBody ShopCommentUpdateRequest request) {
        return shopCommentService.updateComment(commentId, request);
    }
}
