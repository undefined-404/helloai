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

        // 1. 乐观锁拦截器（修复 测试中 updateById 失败的问题）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        return interceptor;
    }

    /**
     * 显式注册 JacksonTypeHandler 到 MyBatis TypeHandlerRegistry，
     * 确保 @TableField(typeHandler=JacksonTypeHandler.class) 在 BaseMapper.selectList 中生效。
     *
     * <p>⚠ 副作用（真实环境已踩坑，迭代记录 §6.132）：Map 与 List 是按类型全局注册，
     * 任何返回值被推断为 Map.class 的自定义查询（如 {@code List<Map<String, Object>>}），
     * MyBatis 会把整行（首列）当作 JSON 交给 JacksonTypeHandler 反序列化，
     * 裸数字/字符串列会抛 MismatchedInputException 导致 500。
     * 因此 Mapper 自定义查询一律禁止返回 List&lt;Map&gt;，必须使用具体 DTO。</p>
     */
    @Bean
    public com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer mybatisPlusConfigurationCustomizer() {
        return configuration -> {
            TypeHandlerRegistry registry = configuration.getTypeHandlerRegistry();
            registry.register(java.util.Map.class, JacksonTypeHandler.class);
            registry.register(java.util.HashMap.class, JacksonTypeHandler.class);
            // sub_task.depends_on 是 List<Long> JSONB 列，SELECT 读出同样需要显式注册
            registry.register(java.util.List.class, JacksonTypeHandler.class);
            registry.register(java.util.ArrayList.class, JacksonTypeHandler.class);
        };
    }
}