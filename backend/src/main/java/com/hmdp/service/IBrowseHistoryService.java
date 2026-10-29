package com.hmdp.service;

import com.hmdp.dto.BrowseHistoryCreateRequest;
import com.hmdp.dto.Result;
import com.hmdp.entity.BrowseHistory;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 浏览历史服务接口
 */
public interface IBrowseHistoryService extends IService<BrowseHistory> {

    /**
     * 记录一次浏览（同一目标去重）
     */
    Result record(BrowseHistoryCreateRequest request);

    /**
     * 分页查询我的浏览历史（按最近浏览时间倒序）
     */
    Result queryMine(Integer current, Integer size);

    /**
     * 清空我的浏览历史
     */
    Result clear();
}
