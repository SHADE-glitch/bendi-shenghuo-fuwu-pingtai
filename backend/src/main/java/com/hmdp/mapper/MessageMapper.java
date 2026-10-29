package com.hmdp.mapper;

import com.hmdp.entity.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息通知 Mapper 接口
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {
    // Custom methods can be added here if needed
}
