package com.helloai.core.agent.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * MCP SESSION_AUTH 定时清理器（v2.6 Q3 任务）。
 *
 * <p><b>触发条件</b>：每 60 秒扫描一次 SESSION_AUTH，
 * 清理 {@code lastAccessAtMs} 早于当前时间 - 30 分钟的过期 entry。</p>
 *
 * <p><b>为什么不用 SSE 关闭事件？</b>
 * spring-ai 1.1.0 的 SSE 关闭事件无法保证可靠触发（实测某些 servlet 容器下不回调），
 * 因此采用 "TTL + 定期扫描" 主方案，零外部依赖，零行为变更风险。
 * 详细参考项目路线图 v2.6 §F.7.3。</p>
 *
 * <p><b>线程安全</b>：复用 {@link McpAuthContext#evictExpired(long)} 的乐观删除语义
 * （{@code ConcurrentMap.remove(key, expectedValue)} 防止误删正在被并发刷新的 entry）。</p>
 *
 * <p><b>阈值选择</b>：30 分钟 = MCP 长连接典型 idle 超时（vs SSE 默认 30 min），
 * 比 30 min 略大可避免正常长连接被提前清理；具体可在
 * {@code application.yml} 通过 {@code helloai.mcp.session-stale-minutes} 覆盖（TODO 后续）。</p>
 *
 * @author helloai
 * @see McpAuthContext
 * @see McpAuthFilter
 */
@Slf4j
@Component
public class SessionAuthCleaner {

    /** 过期阈值：30 分钟无活动视为可清理。 */
    private static final long STALE_THRESHOLD_MS = TimeUnit.MINUTES.toMillis(30);

    /**
     * 每 60 秒扫一次 SESSION_AUTH。
     *
     * <p>使用 {@code fixedRate} 而非 {@code cron}：避免 cron 解析时区问题；</p>
     * <p>{@code initialDelay=30s} 让服务启动期跳过首次清理，
     * 避免与 {@code McpAuthFilter} 启动期写入的 SESSION_AUTH 发生 race。</p>
     */
    @Scheduled(fixedRate = 60_000L, initialDelay = 30_000L)
    public void cleanupStale() {
        try {
            long cutoff = System.currentTimeMillis() - STALE_THRESHOLD_MS;
            int beforeSize = McpAuthContext.size();
            int removed = McpAuthContext.evictExpired(cutoff);
            if (removed > 0) {
                log.info("SessionAuthCleaner: removed {} stale sessions (threshold={}min, before={}, after={})",
                        removed,
                        TimeUnit.MILLISECONDS.toMinutes(STALE_THRESHOLD_MS),
                        beforeSize,
                        McpAuthContext.size());
            } else if (log.isDebugEnabled()) {
                log.debug("SessionAuthCleaner: no stale sessions (size={})", beforeSize);
            }
        } catch (Exception e) {
            // 清理失败不能影响主流程
            log.warn("SessionAuthCleaner: cleanup failed, will retry next tick", e);
        }
    }
}
