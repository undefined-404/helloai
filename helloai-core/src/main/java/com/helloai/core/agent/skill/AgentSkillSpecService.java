package com.helloai.core.agent.skill;

import java.util.List;

/**
 * Agent 技能规范库（eng-*）解析服务（§5.1 Agent Skill 职责归属 agent 域；Phase 1 Step 1 fix：
 * 由 task 域 {@code PluginSkillSpecService} 迁域而来，LOG-20260904-009）。
 *
 * <p>职责边界：只负责「读任务 required_skills（装箱传入）→ 命中插件标签 → 渲染规范速览段」，
 * 不参与选人匹配（选人由 {@code AgentSelectionConstraints} + {@code SkillNormalizer} 承担）；
 * 渲染结果由 {@code SubTaskExecutionServiceImpl} 拼入执行 Prompt 全局段。</p>
 *
 * <p>与 task 域的解耦：本服务<b>纯函数式</b>——requiredSkills 由调用方（执行命令装箱
 * 传播，见 {@code ExecutionCommand.requiredSkills}）传入，不再反向查询 task，
 * 实现与 task 域零依赖（§6 依赖方向红线：agent 域不持有 task 域引用）。</p>
 *
 * <p>详见 doc/design/HelloAI_DeepSeek_Harness_Skills借鉴方案.md §5.3（P1）。</p>
 */
public interface AgentSkillSpecService {

    /**
     * 解析结果 record（D1=B：SKILL_RESOLVED 事件 payload 字段源 + Prompt 装配共用）。
     *
     * <p>三字段恒在（null/空串规范化），Replay 无需特判。
     *
     * @param requiredSkills 任务声明的原始技能标签列表（不可为 null，空时为 {@link List#of()}）
     * @param matchedLabels 命中且成功加载速览的 eng-* 规范标签（不可为 null，空时为 {@link List#of()}）
     * @param section 渲染后的 Markdown 段（不可为 null，空时为 {@code ""}）
     */
    record ResolvedSpec(List<String> requiredSkills, List<String> matchedLabels, String section) {
        public ResolvedSpec {
            requiredSkills = requiredSkills == null ? List.of() : requiredSkills;
            matchedLabels = matchedLabels == null ? List.of() : matchedLabels;
            section = section == null ? "" : section;
        }
    }

    /**
     * 一次性解析任务平台技能规范（D1=B）：声明 / 命中 / 渲染三件套，命中语义与
     * Prompt 注入事实严格一致（两层过滤：标签命中 + 速览非空）。
     *
     * <p>best-effort：requiredSkills 为 null / 空 / 未命中均返回空三字段。</p>
     *
     * @param requiredSkills 任务声明的原始技能标签列表（装箱传入；null 视为空）
     * @return 解析结果，永不为 null
     */
    ResolvedSpec resolve(List<String> requiredSkills);
}