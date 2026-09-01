package com.helloai.core.planner.prompt;

import com.helloai.common.base.BizException;
import com.helloai.common.config.PromptEnhancerProperties;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.prompt.impl.PromptEnhancerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PromptEnhancer（Planner Chat 输入优化）单元测试（CODE_STYLE V2 §56）。
 *
 * <p>覆盖：成功路径断言 AgentTask 的 systemPrompt / userPrompt / temperature / scene；
 * 功能关闭、空输入、LLM 失败、空输出四条失败路径均抛 BizException 且不触达执行链。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PromptEnhancerService (输入优化)")
class PromptEnhancerServiceTest {

    @Mock
    private PlannerAgentPicker plannerAgentPicker;

    @Mock
    private PlatformAgentExecutionService platformAgentExecutionService;

    private PromptEnhancerProperties properties;

    private PromptEnhancerService promptEnhancerService;

    private Agent agent;

    @BeforeEach
    void setUp() {
        properties = new PromptEnhancerProperties();
        promptEnhancerService = new PromptEnhancerServiceImpl(properties, plannerAgentPicker,
                platformAgentExecutionService);
        agent = new Agent();
        agent.setId(1L);
        agent.setName("planner-test");
    }

    @Test
    @DisplayName("成功路径：模板注入 system、原文注入 user、温度与场景标记透传")
    void enhance_success() {
        when(plannerAgentPicker.pick(null)).thenReturn(agent);
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("## 功能目标\n结构化后的需求", "STOP", "API_KEY_LLM", 100));

        PromptEnhanceResult result = promptEnhancerService.enhance("  帮我做一个导出功能  ");

        assertThat(result.originalPrompt()).isEqualTo("帮我做一个导出功能");
        assertThat(result.optimizedPrompt()).isEqualTo("## 功能目标\n结构化后的需求");

        ArgumentCaptor<AgentTask> captor = ArgumentCaptor.forClass(AgentTask.class);
        verify(platformAgentExecutionService).executeSync(any(Agent.class), captor.capture());
        AgentTask task = captor.getValue();
        assertThat(task.getUserPrompt()).isEqualTo("帮我做一个导出功能");
        assertThat(task.getSystemPrompt()).contains("增强表达，而不是重新定义需求");
        assertThat(task.getTemperature()).isEqualTo(properties.getTemperature());
        assertThat(task.getContext()).containsEntry("scene", "prompt_enhance");
    }

    @Test
    @DisplayName("功能关闭：直接抛 BizException，不触达选型与执行链")
    void enhance_disabled() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> promptEnhancerService.enhance("随便写点什么"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未开启");
        verify(plannerAgentPicker, never()).pick(any());
        verify(platformAgentExecutionService, never()).executeSync(any(Agent.class), any(AgentTask.class));
    }

    @Test
    @DisplayName("空输入：抛 BizException，不触达选型与执行链")
    void enhance_blank() {
        assertThatThrownBy(() -> promptEnhancerService.enhance("   "))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能为空");
        verify(plannerAgentPicker, never()).pick(any());
    }

    @Test
    @DisplayName("LLM 执行失败：抛 BizException 并携带失败原因")
    void enhance_llmFailure() {
        when(plannerAgentPicker.pick(null)).thenReturn(agent);
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.failure("上游限流", "ERROR", "API_KEY_LLM"));

        assertThatThrownBy(() -> promptEnhancerService.enhance("一个需求"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("输入优化失败")
                .hasMessageContaining("上游限流");
    }

    @Test
    @DisplayName("LLM 输出为空：视为失败，抛 BizException")
    void enhance_emptyOutput() {
        when(plannerAgentPicker.pick(null)).thenReturn(agent);
        when(platformAgentExecutionService.executeSync(any(Agent.class), any(AgentTask.class)))
                .thenReturn(AgentResult.success("  ", "STOP", "API_KEY_LLM", 10));

        assertThatThrownBy(() -> promptEnhancerService.enhance("一个需求"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("输入优化失败");
    }
}
