package com.helloai.core.agent.domain;

import com.helloai.core.agent.port.MediaRef;
import lombok.Builder;
import lombok.Value;
import lombok.With;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 平台内 Agent 执行任务封装。
 *
 * <p>先收敛到最小执行输入，避免把 Controller / MQ / Prompt 拼接细节直接暴露给 Executor。</p>
 */
@Value
@Builder
@With
public class AgentTask {

    /** 关联子任务 ID；无具体子任务时可为空。 */
    Long subTaskId;

    /** system prompt。 */
    String systemPrompt;

    /** user prompt。 */
    String userPrompt;

    /** 执行上下文。 */
    @Builder.Default
    Map<String, Object> context = Collections.emptyMap();

    /** 执行前要求的能力。 */
    @Builder.Default
    Map<String, Object> requiredCapabilities = Collections.emptyMap();

    /**
     * 平台技能规范标签（eng-*）声明。非空时由契约层（{@code PlatformAgentExecutionServiceImpl}）
     * 统一 resolve 注入 {@code systemPrompt}；拆解/审查/报告三条同步链声明，子任务执行链
     * 不填（走 {@code ExecutionCommand.requiredSkills} 自拼，保护 SKILL_RESOLVED / TOOL_RESOLVED
     * 与 requiredTools 联动）。
     */
    @Builder.Default
    List<String> skills = Collections.emptyList();

    /**
     * 采样温度（可选）。null = 使用模型默认值（现有全部链路行为不变）；
     * 仅稳定改写类辅助场景（如 PromptEnhancer 输入优化）显式设置低值。
     */
    Double temperature;

    /**
     * 本轮要送给模型看的媒体<b>引用</b>列表（`VU-9` 对话面 / `VU-2` 业务面共用）。
     *
     * <p><b>只存引用、不存字节</b>（不变量 I-3）：字节在<b>请求装配层</b>按「本次实际调用的
     * 模型是否支持图片」判过之后才读。理由有两条，任一条都足以否定「构造时就装好 {@code Media}」：</p>
     * <ul>
     *   <li><b>能力判定必须在知道实际模型之后</b>（不变量 I-2，成因形态见 OpenCode 官方 issue
     *       {@code anomalyco/opencode#60736}：note 是每次构建请求时按实际使用的模型加的，
     *       「前端选中 {@code deepseek-flash}」≠「请求实际发给了支持图的模型」）。在
     *       {@code AgentTask} 构造时判，就复刻了那条 issue；</li>
     *   <li><b>白读字节</b>：{@code AttachmentMediaAssembler} 装一次最多 5 张 × 5MB；模型不支持
     *       视觉时这些字节全白读，且会把 {@code AgentTask} 这个纯粹的任务意图载体撑成数据搬运工。</li>
     * </ul>
     *
     * <p><b>默认空表而非 {@code null}</b>：全部既有 {@code AgentTask} 构造点（19 处）不填本字段
     * ⇒ 空表即「零媒体」，与改造前逐字等价（{@code ChatClient} 仍走原 {@code .user(String)} 路径）。
     * {@code @Builder.Default} 保证 {@code builder()} 不填时也是空表而不是 {@code null}。</p>
     *
     * <p><b>与 {@code AgentContext.media}（`VU-1`）的分工</b>：那条是<b>执行链</b>
     * （{@code ExecutionCommand → AgentContext → RuntimeTurnExecutor → AgentLoopInput}），
     * 本字段是<b>对话面 / 业务面</b>（{@code AgentTask → ApiKeyAgentExecutor → ChatClient}）。
     * 两条链没有共用节点，故各有一处载体，<b>不是重复抽象</b>。</p>
     */
    @Builder.Default
    List<MediaRef> mediaRefs = Collections.emptyList();
}
