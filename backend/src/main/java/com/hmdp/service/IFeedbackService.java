package com.hmdp.service;

import com.hmdp.dto.FeedbackCreateRequest;
import com.hmdp.dto.Result;
import com.hmdp.entity.Feedback;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 意见反馈服务接口
 */
public interface IFeedbackService extends IService<Feedback> {

    /**
     * 提交反馈
     */
    Result submit(FeedbackCreateRequest request);

    /**
     * 分页查询我的反馈
     */
    Result queryMine(Integer current, Integer size);

    /**
     * 删除我的反馈（仅本人，越权按「反馈不存在或无权操作」应答）
     *
     * @param feedbackId 反馈id
     * @return 删除结果
     */
    Result deleteMine(Long feedbackId);
}
