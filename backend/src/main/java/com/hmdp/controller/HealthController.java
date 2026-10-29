package com.hmdp.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查控制器
 * <p>
 * 提供应用健康状态检查的 REST API 接口，
 * 返回应用名称、端口、时间戳等运行信息，
 * 用于负载均衡、运维监控和连通性检测。
 * </p>
 */
@RestController
public class HealthController {

    @Value("${spring.application.name}")
    private String applicationName;

    @Value("${server.port}")
    private String serverPort;

    /**
     * 应用健康检查接口
     * <p>返回应用的基本运行信息，如名称、端口和当前状态</p>
     *
     * @return 包含应用状态、名称、端口、时间戳及消息的 Map
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("application", applicationName);
        response.put("port", serverPort);
        response.put("timestamp", System.currentTimeMillis());
        response.put("message", "Application is running successfully");
        return response;
    }

    /**
     * 连通性测试接口（Ping-Pong）
     *
     * @return 包含 "PONG" 状态和时间戳的 Map
     */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "PONG");
        response.put("timestamp", System.currentTimeMillis());
        return response;
    }
}