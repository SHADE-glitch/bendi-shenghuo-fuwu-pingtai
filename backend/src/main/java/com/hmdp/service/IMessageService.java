package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Message;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 消息通知服务接口
 */
public interface IMessageService extends IService<Message> {

    /**
     * 发送一条站内消息（关注 / 点赞 / 评论等触发）
     * <p>
     * 若接收者为空或与发送者相同，则静默跳过，避免给自己发通知。
     * </p>
     *
     * @param toUserId  接收者用户id
     * @param senderId  触发者用户id（可为空，表示系统消息）
     * @param type      消息类型：1-点赞 2-关注 3-评论
     * @param relatedId 关联对象id（笔记id / 用户id）
     * @param content   消息内容
     */
    void send(Long toUserId, Long senderId, Integer type, Long relatedId, String content);

    /**
     * 将当前登录用户的全部未读消息标记为已读
     *
     * @return 本次标记的条数
     */
    Result<Integer> markAllRead();
}
