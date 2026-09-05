package com.helloai.job.task;

import com.helloai.common.config.AgentInboxProperties;
import com.helloai.core.agent.service.AgentInboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link InboxExpireCleanupTask} 单元测试（N-008 统一消息生命周期，Phase 2 A3）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>cleanupEnabled=false → noop（逃生口）</li>
 *   <li>正常路径 → 调 archiveExpired(200)（batch 上限常量）</li>
 *   <li>service 抛异常 → 捕获不外抛（不影响下轮扫描，job task 惯例）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InboxExpireCleanupTask")
class InboxExpireCleanupTaskTest {

    @Mock
    private AgentInboxService agentInboxService;

    private AgentInboxProperties properties;
    private InboxExpireCleanupTask task;

    @BeforeEach
    void setUp() {
        properties = new AgentInboxProperties();
        properties.setCleanupEnabled(true);
        task = new InboxExpireCleanupTask(agentInboxService, properties);
    }

    @Nested
    @DisplayName("scan")
    class Scan {

        @Test
        @DisplayName("cleanupEnabled=false：直接返回，不触碰 service（逃生口）")
        void shouldSkipWhenDisabled() {
            properties.setCleanupEnabled(false);

            task.scan();

            verifyNoInteractions(agentInboxService);
        }

        @Test
        @DisplayName("正常路径：以 batch 上限 200 调 archiveExpired")
        void shouldCallArchiveExpiredWithBatchLimit() {
            task.scan();

            verify(agentInboxService).archiveExpired(200);
        }

        @Test
        @DisplayName("service 抛异常：捕获不外抛（告警日志，不影响下轮扫描）")
        void shouldSwallowServiceException() {
            when(agentInboxService.archiveExpired(anyInt()))
                    .thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> task.scan()).doesNotThrowAnyException();
            verify(agentInboxService).archiveExpired(200);
        }
    }
}
