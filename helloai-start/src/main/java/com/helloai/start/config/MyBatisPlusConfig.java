package com.helloai.start.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
// 注意：mybatis-plus 3.5.9 的 namespace 是 'mybatisplus'（不带连字符）
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 拦截器配置。
 *
 * 关键点：
 * - 必须注册 OptimisticLockerInnerInterceptor，否则 @Version 字段更新会失败
 *   （表现为 BindingException: Parameter 'MP_OPTLOCK_VERSION_ORIGINAL' not found）
 * - 分页插件默认由 spring-boot-starter 自带，这里只需补充乐观锁
 * - 通过 ConfigurationCustomizer 显式注册 JacksonTypeHandler，
 *   解决 agent_outbox_event.payload 等 JSONB 列 SELECT 读出为 null 的问题
 */
@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 乐观锁拦截器（修复 v1.1 测试中 updateById 失败的问题）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        return interceptor;
    }

    /**
     * 显式注册 JacksonTypeHandler 到 MyBatis TypeHandlerRegistry，
     * 确保 @TableField(typeHandler=JacksonTypeHandler.class) 在 BaseMapper.selectList 中生效。
     */
    @Bean
    public com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer mybatisPlusConfigurationCustomizer() {
        return configuration -> {
            TypeHandlerRegistry registry = configuration.getTypeHandlerRegistry();
            registry.register(java.util.Map.class, JacksonTypeHandler.class);
            registry.register(java.util.HashMap.class, JacksonTypeHandler.class);
        };
    }
}