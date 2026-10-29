package com.hmdp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * 优雅停机配置
 * <p>
 * 应用关闭时的资源清理钩子。真正的优雅停机（停止接收新请求、等待请求处理完成）
 * 由 Spring Boot 内建的 server.shutdown=graceful 机制负责。
 * </p>
 */
@Slf4j
@Configuration
public class GracefulShutdownConfig {

    @PreDestroy
    public void onShutdown() {
        log.info("=== Application shutdown initiated ===");
        try {
            com.hmdp.utils.UserHolder.removeUser();
        } catch (Exception e) {
            log.error("Failed to clean ThreadLocal", e);
        }
        log.info("=== Resources cleaned, shutting down ===");
    }
}
