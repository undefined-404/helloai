package com.helloai.core.task.service;

import java.util.List;

/**
 * 平台外部技能规范库（eng-*）服务——把 DeepSeek Harness 借鉴的工程纪律
 * 以平台自命名 `eng-` 前缀规范（classpath:skills/plugins/*.md）按任务所需技能下发执行侧。
 *
 * <p>职责边界：只负责「读任务 required_skills → 命中插件标签 → 渲染规范速览段」，
 * 不参与选人匹配（选人由 {@code AgentSelectionConstraints} + {@code SkillNormalizer} 承担）；
 * 渲染结果由 {@code SubTaskExecutionServiceImpl} 拼入执行 Prompt 全局段。</p>
 *
 * <p>详见 doc/design/HelloAI_DeepSeek_Harness_Skills借鉴方案.md §5.3（P1）。</p>
 */
public interface PluginSkillSpecService {

    /**
     * 解析结果 record（Phase 1 T2，D1=B：SKILL_RESOLVED 事件 payload 字段源 + Prompt 装配共用）。
     *
     * <p>三字段恒在（null/空串规范化），Replay 无需特判。
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
     * 一次性解析任务平台技能规范：返回声明 / 命中 / 渲染文本三件套（Phase 1 T2 D1=B）。
     *
     * <p>best-effort：任务不存在 / 无 required_skills / 未命中 / 文件缺失均返回空三字段，
     * 绝不阻断执行链（与 {@link #renderSection} 既有 best-effort 纪律一致）。</p>
     *
     * <p>命中语义：「实际注入的 eng-* 标签」= 两层过滤后——① 标签命中（{@code SkillNormalizer.normalizeAll} +
     * {@code KNOWN_SPECS} keySet.containsAll），② 速览非空（{@code loadSpeedSummary} 返回非空白）。
     * 与 {@link #renderSection} 的注入事实保持一致：未命中或文件缺失不会出现在 {@code matchedLabels}。</p>
     *
     * @param taskId 主任务 ID（null → 空 ResolvedSpec）
     * @return 三字段恒在的 record
     */
    ResolvedSpec resolve(Long taskId);

    /**
     * 按任务所需技能渲染平台技能规范段（Markdown 章节）。
     *
     * <p>任务 {@code required_skills}（归一化后）命中已登记的 eng-* 插件标签时，
     * 把对应规范文件的「执行速览」部分（首个 {@code ---} 之前）渲染进返回文本；
     * 未命中、任务不存在或读取失败时返回空串（best-effort，绝不阻断执行链）。</p>
     *
     * <p>Phase 1 起改为 {@link #resolve(Long)} 内部调用：保留方法仅为向后兼容（仅取
     * {@code section} 字段）。调用方迁移：直接调 {@code resolve(taskId).section()}。</p>
     *
     * @param taskId 主任务 ID
     * @return 平台技能规范 Markdown 段；无内容时返回空串
     */
    String renderSection(Long taskId);
}
