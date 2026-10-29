package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IFeedbackService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 意见反馈管理控制器
 * <p>补「反馈可撤回」缺口，仅本人可删除自己的反馈。</p>
 */
@RestController
@RequestMapping("/feedbacks")
public class FeedbackManageController {

    @Resource
    private IFeedbackService feedbackService;

    /**
     * 删除我的反馈（仅本人）
     */
    @DeleteMapping("/{feedbackId}")
    public Result delete(@PathVariable("feedbackId") Long feedbackId) {
        return feedbackService.deleteMine(feedbackId);
    }
}
