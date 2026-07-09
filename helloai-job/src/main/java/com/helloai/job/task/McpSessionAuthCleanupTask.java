package com.helloai.job.task;

import com.helloai.core.mcp.McpAuthContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class McpSessionAuthCleanupTask {

    @Value("${helloai.mcp.session-auth-ttl-minutes:120}")
    private long ttlMinutes;

    @Scheduled(fixedRateString = "${helloai.mcp.session-auth-cleanup-rate-ms:300000}")
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - ttlMinutes * 60_000L;
        int removed = McpAuthContext.evictExpired(cutoff);
        if (removed > 0) {
            log.info("MCP SESSION_AUTH cleanup: removed={}, remaining={}", removed, McpAuthContext.size());
        }
    }
}

