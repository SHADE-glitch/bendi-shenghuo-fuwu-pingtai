package com.hmdp.service.impl;

import com.hmdp.dto.ErrorCode;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Message;
import com.hmdp.mapper.MessageMapper;
import com.hmdp.service.IMessageService;
import com.hmdp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/**
 * 消息通知服务实现类
 */
@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message> implements IMessageService {

    @Override
    public void send(Long toUserId, Long senderId, Integer type, Long relatedId, String content) {
        // 接收者缺失或给自己发消息，直接跳过
        if (toUserId == null || toUserId.equals(senderId)) {
            return;
        }
        Message message = new Message();
        message.setUserId(toUserId);
        message.setSenderId(senderId);
        message.setType(type);
        message.setRelatedId(relatedId);
        message.setContent(content);
        message.setIsRead(0);
        message.setCreateTime(new Timestamp(System.currentTimeMillis()));
        this.save(message);
    }

    @Override
    public Result<Integer> markAllRead() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(ErrorCode.AUTH_REQUIRED);
        }
        long unread = query()
                .eq("user_id", user.getId())
                .eq("is_read", 0)
                .count();
        if (unread == 0) {
            return Result.ok(0);
        }
        // 只更新当前用户自己的未读消息，天然避免越权
        update()
                .set("is_read", 1)
                .eq("user_id", user.getId())
                .eq("is_read", 0)
                .update();
        return Result.ok((int) unread);
    }
}
