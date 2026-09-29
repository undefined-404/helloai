package com.helloai.it;

import com.helloai.HelloAIApplication;
import com.helloai.common.config.AgentProviderProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * B 级集成测试专用启动类（2026-09-29 引入，审计建议 #5）。
 *
 * <p>与 {@code HelloAIApplication} 的唯一差异：<b>不启用 {@code @EnableScheduling}</b>。
 * 生产主类挂载了 3 个 {@code @Scheduled} 巡检（OutboxRelayTask 1s / OrphanScanTask 60s /
 * InboxExpireCleanupTask 5min 等），测试环境若自动触发会与断言产生竞态——本类将调度显式
 * 交给测试代码（如 {@code OutboxRelayTask.relay()} 手动调用），保证时序可控
 * （协作规约 §25 验证集合：可控性优先于真实性）。</p>
 *
 * <p>其余注解（scanBasePackages / MapperScan 9 包 / EnableAsync）与生产主类逐字对齐，
 * 确保被测装配 == 生产装配（CODE_STYLE §47.2 集成测试：真实 Spring Bean / 事务 / Mapper）。
 * 唯一例外：通过 {@code excludeFilters} 排除 {@link HelloAIApplication} 本身——
 * 本类 {@code scanBasePackages=com.helloai} 会把它当普通组件扫入并连带激活其上的
 * {@code @EnableScheduling}（2026-09-29 本机 Docker 实跑暴露：OutboxRelayTask 1s /
 * ExecutionCommandPoller 1s 自动运行与断言竞态），必须显式排除才能兑现
 * 「测试无调度、调度由用例手动触发」的时序可控承诺。</p>
 */
@SpringBootApplication
@ComponentScan(basePackages = "com.helloai",
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = HelloAIApplication.class))
@EnableConfigurationProperties(AgentProviderProperties.class)
@MapperScan({
        "com.helloai.core.agent.mapper",
        "com.helloai.core.agent.session.mapper",
        "com.helloai.core.agent.quality.mapper",
        "com.helloai.core.task.mapper",
        "com.helloai.core.task.workflow.mapper",
        "com.helloai.core.review.mapper",
        "com.helloai.core.system.mapper",
        "com.helloai.core.planner.mapper",
        "com.helloai.core.planner.memory.mapper"
})
@EnableAsync(proxyTargetClass = true)
public class ItTestApplication {

    static {
        // 与 HelloAIApplication.main() 相同的 CGLIB 策略：禁用类缓存，规避异常退出后
        // cglib cache item 无法加载的启动失败（见 memory "Spring Boot CGLIB 缓存污染诊断与修复"）
        if (System.getProperty("cglib.cache.classes") == null) {
            System.setProperty("cglib.cache.classes", "false");
        }
    }
}