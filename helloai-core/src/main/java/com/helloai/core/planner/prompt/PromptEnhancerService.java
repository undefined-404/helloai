package com.helloai.core.planner.prompt;

/**
 * Planner 输入优化（PromptEnhancer）服务。
 *
 * <p>独立的辅助能力，不挂进 Planner 状态机、不触发任何执行链路：
 * 仅"当前输入 → LLM → 优化输入"的单向改写（CODE_STYLE V2 §32.1）。
 * 第一版只优化当前输入，不引入会话历史 / MCP / Skill / Planner State / DAG。</p>
 */
public interface PromptEnhancerService {

    /**
     * 优化用户当前输入为更清晰、结构化、适合 Planner / Coding Agent 理解的表达。
     *
     * @param prompt 用户当前输入（原文，非空）
     * @return 原文 + 优化后版本；功能关闭 / 输入为空 / LLM 调用失败时抛 {@link com.helloai.common.base.BizException}
     */
    PromptEnhanceResult enhance(String prompt);
}
