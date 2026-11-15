package com.helloai.core.agent.port;

/**
 * 「借某个 Agent 的模型做一次单轮判定」的端口（`TD-16e-3-2`）。
 *
 * <p><b>为什么需要端口而不是让调用方自己建模型</b>：语义门（{@code core.planner}）要在确认接口里
 * 用「产出这批草案的 planner agent」自己的模型判一次一致性。若让 planner 直接依赖
 * {@code Agent} 实体 + 凭据解析器，就是 §7.2 明令禁止的**跨域实体泄漏**，而且会把
 * 「provider 从哪来、缺凭据是否 fail-close」的口径复制出第二份。端口只暴露一个中性动作：
 * 给 agentId 与两段文本，拿回一段正文。</p>
 *
 * <p><b>刻意不返回 {@code ChatModel}</b>：返回模型等于把 Spring AI 的调用细节与错误形态
 * 交给上层，上层就会长出自己的重试/解析逻辑；单轮无工具、无会话的判定是唯一被允许的用法。</p>
 */
public interface SemanticJudgePort {

    /**
     * 用 {@code agentId} 对应 Agent 的模型做一次单轮调用（无工具、无历史、温度 0）。
     *
     * @param systemPrompt 角色与规则段（含输出 JSON 契约）
     * @param userPrompt   本次判定的事实材料
     * @return 模型正文（可能为空串，用方按「无法判定」处理）
     * @throws com.helloai.common.base.BizException Agent 不存在 / 无可用凭据
     */
    String judgeOnce(Long agentId, String systemPrompt, String userPrompt);
}
