package com.hmdp.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.hmdp.utils.SystemConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类
 * <p>
 * 注册 MyBatis-Plus 拦截器，当前启用 MySQL 分页插件，
 * 并设置全局分页上限，避免外部传入过大的 size 造成全表扫描 / 内存放大。
 * </p>
 */
@Configuration
public class MybatisConfig {

    /**
     * 注册 MyBatis-Plus 拦截器，添加 MySQL 分页插件（带全局分页上限）。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 全局分页上限：size 超过该值时由插件自动截断，未显式指定 size 的查询不受影响。
        // 取 DEFAULT_PAGE_SIZE(100)，与店铺列表等既有默认页大小保持一致。
        pagination.setMaxLimit((long) SystemConstants.DEFAULT_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
