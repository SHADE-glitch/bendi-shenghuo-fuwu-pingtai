package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.CollectionUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.FeedbackCreateRequest;
import com.hmdp.dto.FeedbackDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Feedback;
import com.hmdp.mapper.FeedbackMapper;
import com.hmdp.service.IFeedbackService;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 意见反馈服务实现类
 */
@Service
public class FeedbackServiceImpl extends ServiceImpl<FeedbackMapper, Feedback> implements IFeedbackService {

    @Override
    public Result submit(FeedbackCreateRequest request) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Feedback feedback = new Feedback()
                .setUserId(userId)
                .setContent(sanitize(request.getContent()))
                .setContact(request.getContact())
                .setType(request.getType() != null ? request.getType() : 0)
                .setStatus(0);
        save(feedback);
        return Result.ok(feedback.getId());
    }

    @Override
    public Result queryMine(Integer current, Integer size) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Page<Feedback> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, size));
        List<Feedback> records = page.getRecords();
        if (CollectionUtils.isEmpty(records)) {
            return Result.ok(Collections.emptyList(), page.getTotal());
        }
        List<FeedbackDTO> list = records.stream().map(this::buildDTO).collect(Collectors.toList());
        return Result.ok(list, page.getTotal());
    }

    @Override
    public Result deleteMine(Long feedbackId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        Feedback feedback = getById(feedbackId);
        // 不存在或非本人反馈，统一文案，不泄露存在性
        if (feedback == null || !userId.equals(feedback.getUserId())) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "反馈不存在或无权操作");
        }
        removeById(feedbackId);
        return Result.ok();
    }

    private FeedbackDTO buildDTO(Feedback feedback) {
        FeedbackDTO dto = new FeedbackDTO();
        dto.setId(feedback.getId());
        dto.setContent(feedback.getContent());
        dto.setContact(feedback.getContact());
        dto.setType(feedback.getType());
        dto.setStatus(feedback.getStatus());
        dto.setCreateTime(feedback.getCreateTime());
        return dto;
    }

    private String sanitize(String input) {
        if (input == null) {
            return null;
        }
        return input.replace("<", "&lt;").replace(">", "&gt;");
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }
}
