package com.hmdp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.MessageListDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Message;
import com.hmdp.entity.User;
import com.hmdp.service.IMessageService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import jakarta.validation.constraints.Min;
import jakarta.annotation.Resource;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 消息通知控制器
 * <p>提供消息列表查询、标记已读等 REST API 接口</p>
 */
@Validated
@RestController
@RequestMapping("/messages")
public class MessageController {
    
    @Resource
    private IMessageService messageService;

    @Resource
    private IUserService userService;
    
    /**
     * 分页查询消息列表
     * <p>
     * {@code isRead} 不传时返回全部消息（前端「全部」Tab 使用）；
     * 传 0 / 1 时分别筛选未读 / 已读。
     * </p>
     */
    @GetMapping("/list")
    public Result<List<MessageListDTO>> listMessages(
            @RequestParam(value = "type", required = false) Integer type,
            @RequestParam(value = "isRead", required = false) Integer isRead,
            @RequestParam(value = "current", defaultValue = "1") @Min(value = 1, message = "页码 current 必须大于等于 1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") @Min(value = 1, message = "每页条数 size 必须大于等于 1") Integer size) {
        
        Long userId = UserHolder.getUser().getId();
        Page<Message> page = messageService.query()
                .eq("user_id", userId)
                .eq(type != null, "type", type)
                .eq(isRead != null, "is_read", isRead)
                .orderByDesc("create_time")
                .page(new Page<>(current, size));

        List<Message> records = page.getRecords();

        // 批量加载发送者，避免 N+1 查询
        Set<Long> senderIds = records.stream()
                .map(Message::getSenderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, UserDTO> senderMap = senderIds.isEmpty()
                ? Collections.emptyMap()
                : userService.listByIds(senderIds).stream()
                    .collect(Collectors.toMap(User::getId, this::toUserDTO, (a, b) -> a));

        List<MessageListDTO> messages = records.stream().map(m -> {
            MessageListDTO dto = new MessageListDTO();
            dto.setId(m.getId());
            dto.setContent(m.getContent());
            dto.setType(m.getType());
            dto.setRelatedId(m.getRelatedId());
            dto.setIsRead(m.getIsRead());
            dto.setCreateTime(m.getCreateTime());
            dto.setSender(senderMap.get(m.getSenderId()));
            return dto;
        }).collect(Collectors.toList());
        
        Result<List<MessageListDTO>> result = Result.ok(messages);
        result.setTotal(page.getTotal());
        return result;
    }

    private UserDTO toUserDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setNickName(user.getNickName());
        dto.setIcon(user.getIcon());
        return dto;
    }
    
    /**
     * 标记消息为已读
     */
    @PostMapping("/read/{messageId}")
    public Result<Void> markAsRead(@PathVariable Long messageId) {
        Long userId = UserHolder.getUser().getId();
        // 仅允许标记「自己」的消息，防止越权修改他人消息状态
        boolean updated = messageService.update()
            .set("is_read", 1)
            .eq("id", messageId)
            .eq("user_id", userId)
            .update();
        if (!updated) {
            return Result.fail(ErrorCode.PARAM_INVALID.getCode(), "消息不存在或无权操作");
        }
        return Result.ok();
    }
    
    /**
     * 获取未读消息数量
     */
    @GetMapping("/unread/count")
    public Result<Integer> getUnreadCount() {
        long count = messageService
            .query()
            .eq("user_id", UserHolder.getUser().getId())
            .eq("is_read", 0)
            .count();
        return Result.ok((int) count);
    }
}
