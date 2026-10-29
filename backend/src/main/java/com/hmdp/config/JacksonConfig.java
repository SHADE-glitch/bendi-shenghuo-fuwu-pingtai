package com.hmdp.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Jackson 配置类
 * <p>
 * 将 {@code Long}/{@code long} 统一序列化为字符串，避免雪花 ID（18 位）超过
 * JavaScript Number.MAX_SAFE_INTEGER 导致前端精度丢失（订单详情查不到等）。
 * </p>
 */
@Configuration
public class JacksonConfig {

    /**
     * 全局 Long 类型序列化为字符串
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}