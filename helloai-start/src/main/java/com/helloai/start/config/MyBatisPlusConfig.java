package com.helloai.start.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
// 注意：mybatis-plus 3.5.9 的 namespace 是 'mybatisplus'（不带连字符）
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 拦截器配置。
 *
 * 关键点：
 * - 必须注册 OptimisticLockerInnerInterceptor，否则 @Version 字段更新会失败
 *   （表现为 BindingException: Parameter 'MP_OPTLOCK_VERSION_ORIGINAL' not found）
 * - 分页插件默认由 spring-boot-starter 自带，这里只需补充乐观锁
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
}