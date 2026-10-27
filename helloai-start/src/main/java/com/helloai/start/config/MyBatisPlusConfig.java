package com.helloai.start.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.helloai.core.shared.handler.PgJsonbTypeHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
// 注意：mybatis-plus 3.5.9 的 namespace 是 'mybatisplus'（不带连字符）
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 拦截器配置。
 *
 * 关键点：
 * - 必须注册 OptimisticLockerInnerInterceptor，否则 @Version 字段更新会失败
 *   （表现为 BindingException: Parameter 'MP_OPTLOCK_VERSION_ORIGINAL' not found）
 * - 分页插件不随 starter 自动注册，需显式加 PaginationInnerInterceptor，
 *   否则 IPage.page() 不执行 COUNT、total 恒为 0 且不分页（G-012 用户分页 API 实测暴露）
 * - JSONB 读侧映射由「含 JSONB 字段的实体 @TableName(autoResultMap=true) +
 *   @TableField(typeHandler=PgJsonbTypeHandler.class)」驱动（2026-10-27 起不再全局注册
 *   Map/List → PgJsonbTypeHandler——全局注册会劫持 mpFillEt 等参数绑定，见方法注释）
 * - 通过 ConfigurationCustomizer 向 JacksonTypeHandler 静态注入带 JavaTimeModule 的
 *   ObjectMapper：MyBatis-Plus 内置 JacksonTypeHandler 默认 new ObjectMapper()（裸 mapper，
 *   无 jsr310 模块），任何 JSONB 序列化 OffsetDateTime（如 BaseEntity.updateTime）都会抛
 *   InvalidDefinitionException（editMessageById 500 实测暴露）。注册进静态字段后，
 *   PgJsonbTypeHandler / UncertaintyListTypeHandler / TaskIteration 直用
 *   内置 JacksonTypeHandler 的字段全部受益（写侧序列化 + 读侧反序列化）。
 */
@Configuration
public class MyBatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1. 分页拦截器（PostgreSQL）
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));

        // 2. 乐观锁拦截器（修复 测试中 updateById 失败的问题）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        return interceptor;
    }

    /**
     * 向 JacksonTypeHandler 静态注入带 JavaTimeModule 的 ObjectMapper。
     *
     * <p>MyBatis-Plus 内置 {@link JacksonTypeHandler} 默认 {@code new ObjectMapper()}（裸 mapper，
     * 无 jsr310 模块），任何 JSONB 序列化 OffsetDateTime（如 BaseEntity.updateTime）都会抛
     * InvalidDefinitionException（editMessageById 500 实测暴露）。注册进静态字段后，
     * PgJsonbTypeHandler / UncertaintyListTypeHandler / 实体 @TableField(typeHandler=...) 全部受益
     * （写侧序列化 + 读侧反序列化）。</p>
     *
     * <p><b>2026-10-27 根治（移除全局 Map/List 注册）</b>：此前此处把
     * {@code Map/HashMap/List/ArrayList → PgJsonbTypeHandler} 全局注册到 TypeHandlerRegistry，
     * 本意是让 JSONB 列 SELECT 读出时命中自定义 handler，但副作用致命——
     * MyBatis 对 {@code removeByIds}/{@code updateById} 内部构造的 {@code mpFillEt} 参数 map 推断
     * javaType=HashMap，绑定 {@code #{mpFillEt.updateBy}} 占位符时按 javaType 命中全局注册，
     * setNonNullParameter 收到整个 map → 序列化 map 内含 BaseEntity.updateTime（OffsetDateTime）
     * → 裸 mapper 炸 InvalidDefinitionException（editMessageById 500 实测暴露）。</p>
     *
     * <p>根治方式：<b>移除全局注册</b>，改由「含 JSONB 字段的实体 {@code @TableName(autoResultMap=true)}
     * + 字段级 {@code @TableField(typeHandler=PgJsonbTypeHandler.class)}」驱动读侧映射。
     * 全局注册的劫持面（任何 javaType=Map/List 的参数/返回绑定）彻底清零；
     * 写侧 JSONB 由字段级 typeHandler 覆盖，读侧由 autoResultMap 覆盖。</p>
     */
    @Bean
    public com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer mybatisPlusConfigurationCustomizer() {
        return configuration -> {
            // 注入带 jsr310 支持的 ObjectMapper 到 JacksonTypeHandler 静态字段（懒加载时序安全：
            // SqlSessionFactory 创建时执行，早于一切 DB 访问；toJson/parse 都懒读该静态字段）。
            ObjectMapper mapper = new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            JacksonTypeHandler.setObjectMapper(mapper);
            // ⚠ 不再全局注册 Map/List → PgJsonbTypeHandler：会劫持 mpFillEt 等参数绑定（见类注释）。
            //    读侧 JSONB 由实体 @TableName(autoResultMap=true) + @TableField(typeHandler=...) 覆盖。
        };
    }
}