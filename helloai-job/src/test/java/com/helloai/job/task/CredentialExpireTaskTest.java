package com.helloai.job.task;

import com.helloai.common.config.CredentialLifecycleProperties;
import com.helloai.core.system.service.CredentialVaultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link CredentialExpireTask} 单元测试（N-004 Credential Vault 收口，Phase 2 B2）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>expireScanEnabled=false → noop（逃生口）</li>
 *   <li>正常路径 → 调 expireOverdue(100)（batch 上限常量）</li>
 *   <li>service 抛异常 → 捕获不外抛（不影响下轮扫描，job task 惯例）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialExpireTask")
class CredentialExpireTaskTest {

    @Mock
    private CredentialVaultService credentialVaultService;

    private CredentialLifecycleProperties properties;
    private CredentialExpireTask task;

    @BeforeEach
    void setUp() {
        properties = new CredentialLifecycleProperties();
        properties.setExpireScanEnabled(true);
        task = new CredentialExpireTask(credentialVaultService, properties);
    }

    @Nested
    @DisplayName("scan")
    class Scan {

        @Test
        @DisplayName("expireScanEnabled=false：直接返回，不触碰 service（逃生口）")
        void shouldSkipWhenDisabled() {
            properties.setExpireScanEnabled(false);

            task.scan();

            verifyNoInteractions(credentialVaultService);
        }

        @Test
        @DisplayName("正常路径：以 batch 上限 100 调 expireOverdue")
        void shouldCallExpireOverdueWithBatchLimit() {
            task.scan();

            verify(credentialVaultService).expireOverdue(100);
        }

        @Test
        @DisplayName("service 抛异常：捕获不外抛（告警日志，不影响下轮扫描）")
        void shouldSwallowServiceException() {
            when(credentialVaultService.expireOverdue(anyInt()))
                    .thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> task.scan()).doesNotThrowAnyException();
            verify(credentialVaultService).expireOverdue(100);
        }
    }
}
