package com.helloai.core.task.service;

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
     * 按任务所需技能渲染平台技能规范段（Markdown 章节）。
     *
     * <p>任务 {@code required_skills}（归一化后）命中已登记的 eng-* 插件标签时，
     * 把对应规范文件的「执行速览」部分（首个 {@code ---} 之前）渲染进返回文本；
     * 未命中、任务不存在或读取失败时返回空串（best-effort，绝不阻断执行链）。</p>
     *
     * @param taskId 主任务 ID
     * @return 平台技能规范 Markdown 段；无内容时返回空串
     */
    String renderSection(Long taskId);
}
