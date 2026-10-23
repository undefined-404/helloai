package com.helloai.core.planner.service;

import com.helloai.core.agent.port.AgentProfileSnapshot;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyReply;

/**
 * 澄清/自由对话/终稿直出的<b>模板渲染 + 描述段守卫</b>接口（RM9-c 从
 * {@link RequirementClarifyService} 实现类拆出的职责契约，只读、不写会话状态）。
 *
 * <p>实现见 {@code com.helloai.core.planner.service.impl.ClarifyPromptRenderServiceImpl}。
 * 承接三块：三模板渲染（CLARIFY / CHAT / FINALIZE）、联合决策模板渲染、终稿 description
 * 四小节纠偏守卫（P1-3，fail-open）。</p>
 */
public interface ClarifyPromptRenderService {

    /** 渲染澄清方案主回复 Prompt（CLARIFY 模板，JSON 协议）。 */
    String renderClarifyPrompt(Long conversationId, String webSearchContext);

    /** 渲染自由对话主回复 Prompt（CHAT 模板，纯文本）。 */
    String renderChatPrompt(Long conversationId, String webSearchContext);

    /**
     * 带附件可见性控制的 CHAT 渲染（`ATT-5`）。
     *
     * <p>联合决策判定 {@code attachment_intent=none}（本轮不涉及附件）时传 {@code false}：
     * 附件<b>清单</b>仍注入（模型要知道有文件），但<b>正文</b>不注入——避免「问天气也带
     * 200k 手册」把上下文顶爆。默认重载 {@code (id, ctx)} 等价于 {@code includeAttachment=true}，
     * 未改造的调用方行为零变化。</p>
     *
     * @param includeAttachment 是否注入附件正文
     */
    String renderChatPrompt(Long conversationId, String webSearchContext, boolean includeAttachment);

    /** 渲染终稿直出 Prompt（/task 直达拆解模板）。 */
    String renderFinalizePrompt(Long conversationId, String webSearchContext);

    /** 渲染联合决策 Prompt（本轮消息 + 近期历史 + 搜索模式提示）。 */
    String renderDecisionPrompt(RequirementConversation conversation, String userMessage);

    /**
     * 终稿 description 四小节纠偏重试（仅 1 次，fail-open 不阻断建任务）。
     *
     * @param scene AgentTask context 中的 scene 值（requirement_finalize / requirement_clarify）
     * @return 重试后小节齐全的 final 结果；未触发重试或重试无效时原样返回首轮结果
     */
    ClarifyReply applyDescriptionSectionGuard(RequirementConversation conversation,
                                              AgentProfileSnapshot planner, String prompt,
                                              String scene, ClarifyReply firstReply);
}
