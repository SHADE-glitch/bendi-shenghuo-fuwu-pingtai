package com.hmdp.controller;

import com.hmdp.dto.FeedbackCreateRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.IFeedbackService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 意见反馈控制器
 * <p>提供反馈提交与「我的反馈」查询接口</p>
 */
@Validated
@RestController
@RequestMapping("/feedbacks")
public class FeedbackController {

    @Resource
    private IFeedbackService feedbackService;

    /**
     * 提交反馈
     */
    @PostMapping
    public Result submit(@Valid @RequestBody FeedbackCreateRequest request) {
        return feedbackService.submit(request);
    }

    /**
     * 分页查询我的反馈
     */
    @GetMapping("/mine")
    public Result queryMine(
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        return feedbackService.queryMine(current, size);
    }
}
