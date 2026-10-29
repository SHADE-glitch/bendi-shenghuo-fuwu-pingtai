package com.hmdp.dto;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 消息列表项 DTO
 */
@Data
public class MessageListDTO {
    private Long id;
    private String content;
    private Integer type;
    private Long relatedId;
    private Integer isRead;
    private Timestamp createTime;
    
    // 发送者信息
    private UserDTO sender;
}
