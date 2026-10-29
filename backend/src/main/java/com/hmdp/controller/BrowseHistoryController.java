package com.hmdp.controller;

import com.hmdp.dto.BrowseHistoryCreateRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IBrowseHistoryService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 浏览历史控制器
 * <p>提供浏览历史上报、列表查询与清空接口</p>
 */
@Validated
@RestController
@RequestMapping("/browse-history")
public class BrowseHistoryController {

    @Resource
    private IBrowseHistoryService browseHistoryService;

    /**
     * 记录一次浏览（同一目标去重）
     */
    @PostMapping
    public Result record(@Valid @RequestBody BrowseHistoryCreateRequest request) {
        return browseHistoryService.record(request);
    }

    /**
     * 分页查询我的浏览历史
     */
    @GetMapping("/mine")
    public Result queryMine(
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        return browseHistoryService.queryMine(current, size);
    }

    /**
     * 清空我的浏览历史
     */
    @DeleteMapping
    public Result clear() {
        return browseHistoryService.clear();
    }
}
