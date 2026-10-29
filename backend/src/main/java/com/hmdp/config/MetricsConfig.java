package com.hmdp.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 性能监控配置
 * <p>暴露关键业务指标到 Prometheus</p>
 */
@Configuration
public class MetricsConfig {
    
    @Bean
    public Counter seckillOrderCounter(MeterRegistry registry) {
        return Counter.builder("seckill.orders.total")
            .description("Total seckill orders processed")
            .register(registry);
    }
    
    @Bean
    public Timer seckillDurationTimer(MeterRegistry registry) {
        return Timer.builder("seckill.duration")
            .description("Seckill operation duration")
            .publishPercentileHistogram()
            .register(registry);
    }
}
