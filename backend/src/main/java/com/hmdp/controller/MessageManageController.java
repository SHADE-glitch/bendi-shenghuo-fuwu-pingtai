package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IMessageService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息管理控制器
 * <p>补「一键全部已读」缺口，只影响当前登录用户自己的未读消息。</p>
 */
@RestController
@RequestMapping("/messages")
public class MessageManageController {

    @Resource
    private IMessageService messageService;

    /**
     * 将我的全部未读消息标记为已读，返回本次标记的条数
     */
    @PostMapping("/read-all")
    public Result<Integer> readAll() {
        return messageService.markAllRead();
    }
}
