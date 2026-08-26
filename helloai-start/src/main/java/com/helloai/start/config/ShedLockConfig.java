package com.helloai.start.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * ShedLock 定时任务分布式锁（v1.2 §阶段2）。
 *
 * <p>11 个 job 定时任务由 Redis setIfAbsent 自旋锁迁移到 {@code @SchedulerLock}：
 * 本配置通过代理包装 SchedulingConfig 的 ThreadPoolTaskScheduler 实现锁注入，
 * 锁记录写入 Redis（key 前缀 helloai:schedlock:），多实例部署时同一任务仅一个
 * 实例执行，无需改动既有线程池配置。</p>
 *
 * <p>defaultLockAtMostFor=60s 仅作兜底持有时间，各任务的 lockAtMostFor /
 * lockAtLeastFor 按原锁 TTL 口径在 {@code @SchedulerLock} 上显式声明（见各任务类）。</p>
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT60S")
public class ShedLockConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        // environment 作为锁 key 前缀：helloai:schedlock:{任务名}，与 CODE_STYLE §12.2 锁键命名对齐
        return new RedisLockProvider(connectionFactory, "helloai:schedlock");
    }
}