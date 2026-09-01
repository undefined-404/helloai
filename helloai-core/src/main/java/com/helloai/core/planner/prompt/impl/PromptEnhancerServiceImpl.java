package com.helloai.core.planner.prompt.impl;

import com.helloai.common.base.BizException;
import com.helloai.common.config.PromptEnhancerProperties;
import com.helloai.core.agent.domain.AgentResult;
import com.helloai.core.agent.domain.AgentTask;
import com.helloai.core.agent.entity.Agent;
import com.helloai.core.agent.service.PlatformAgentExecutionService;
import com.helloai.core.planner.picker.PlannerAgentPicker;
import com.helloai.core.planner.prompt.PromptEnhanceResult;
import com.helloai.core.planner.prompt.PromptEnhancerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Planner 输入优化实现：复用平台内 LLM 执行链（与澄清/拆解同一选型语义），
 * 不新设计 LLM 框架、不触碰任务执行链路。
 *
 * <p>选型：{@link PlannerAgentPicker#pick(Long)} 传 null 走自动选择（与澄清链一致）；
 * 温度：{@link PromptEnhancerProperties#getTemperature()} 低值稳定改写；
 * 模板：{@code prompts/prompt-enhance.md}，加载范式照
 * {@code RequirementClarifyServiceImpl#renderPrompt}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptEnhancerServiceImpl implements PromptEnhancerService {

    /** 独立 System Prompt 模板（"增强表达而非重新定义需求"）。 */
    private static final String TEMPLATE_PATH = "prompts/prompt-enhance.md";

    private final PromptEnhancerProperties properties;
    private final PlannerAgentPicker plannerAgentPicker;
    private final PlatformAgentExecutionService platformAgentExecutionService;

    @Override
    public PromptEnhanceResult enhance(String prompt) {
        if (!properties.isEnabled()) {
            throw new BizException("输入优化功能未开启");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new BizException("待优化的输入内容不能为空");
        }
        String originalPrompt = prompt.trim();

        Agent agent = plannerAgentPicker.pick(null);
        AgentTask task = AgentTask.builder()
                .systemPrompt(loadSystemPrompt())
                .userPrompt(originalPrompt)
                .temperature(properties.getTemperature())
                .context(Map.of("scene", "prompt_enhance"))
                .build();
        AgentResult result = platformAgentExecutionService.executeSync(agent, task);
        if (!result.isSuccess() || result.getOutput() == null || result.getOutput().isBlank()) {
            String reason = result.getErrorMessage();
            throw new BizException("输入优化失败" + (reason != null && !reason.isBlank() ? ": " + reason : ""));
        }
        log.info("输入优化完成: agentId={}, 原文长度={}, 优化后长度={}",
                agent.getId(), originalPrompt.length(), result.getOutput().length());
        return new PromptEnhanceResult(originalPrompt, result.getOutput().trim());
    }

    /** 加载 classpath 模板（UTF-8），失败语义与澄清链一致。 */
    private String loadSystemPrompt() {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_PATH);
        if (!resource.exists()) {
            throw new BizException("未找到 Prompt 模板: " + TEMPLATE_PATH);
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new BizException("读取 Prompt 模板失败: " + e.getMessage());
        }
    }
}
