package com.helloai.core.agent.executor;

import com.helloai.common.constant.AgentAccessType;
import com.helloai.core.agent.browser.gateway.BrowserAgentGateway;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link BrowserAgentExecutor} C3-S2 单元测试：WEB_BROWSER 路由 + 推送桥接执行。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserAgentExecutor WEB_BROWSER 执行器（C3-S2）")
class BrowserAgentExecutorTest {

    @Mock
    private BrowserAgentGateway gateway;

    private BrowserAgentExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new BrowserAgentExecutor(gateway);
    }

    private Agent agent(AgentAccessType accessType) {
        Agent a = new Agent();
        a.setId(100L);
        a.setAccessType(accessType);
        return a;
    }

    private AgentTask task() {
        return AgentTask.builder()
                .subTaskId(200L)
                .systemPrompt("sys")
                .userPrompt("抓取 https://example.com 首页并截图")
                .build();
    }

    @Test
    @DisplayName("supports：仅 WEB_BROWSER")
    void supportsOnlyWebBrowser() {
        assertThat(executor.supports(agent(AgentAccessType.WEB_BROWSER))).isTrue();
        assertThat(executor.supports(agent(AgentAccessType.API_KEY_LLM))).isFalse();
        assertThat(executor.supports(agent(AgentAccessType.CLI_CLIENT))).isFalse();
        assertThat(executor.supports(null)).isFalse();
    }

    @Test
    @DisplayName("execute：推送成功，回传 output 文本（可为 manifest 产物协议）")
    void executeOk() throws Exception {
        String manifest = "{\"summary\":\"已抓取\",\"files\":[{\"fileName\":\"shot.png\",\"mimeType\":\"image/png\",\"content\":\"base64\"}]}";
        when(gateway.push(agent(AgentAccessType.WEB_BROWSER), task())).thenReturn(manifest);

        AgentResult result = executor.execute(agent(AgentAccessType.WEB_BROWSER), task());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getOutput()).isEqualTo(manifest);
        assertThat(result.getFinishReason()).isEqualTo("STOP");
        assertThat(result.getExecutorName()).isEqualTo("BrowserAgentExecutor");
    }

    @Test
    @DisplayName("execute：推送失败抛异常（上层记录 FAILED，同 ApiKeyAgentExecutor 契约）")
    void executeFail() throws Exception {
        when(gateway.push(agent(AgentAccessType.WEB_BROWSER), task()))
                .thenThrow(new IllegalStateException("webhook 不可达"));

        assertThatThrownBy(() -> executor.execute(agent(AgentAccessType.WEB_BROWSER), task()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webhook 不可达");
    }

    @Test
    @DisplayName("execute：gateway 抛检查型异常 → 包装为运行时异常")
    void executeWrapsCheckedException() throws Exception {
        when(gateway.push(agent(AgentAccessType.WEB_BROWSER), task())).thenThrow(new java.io.IOException("timeout"));

        assertThatThrownBy(() -> executor.execute(agent(AgentAccessType.WEB_BROWSER), task()))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(java.io.IOException.class);
    }
}
