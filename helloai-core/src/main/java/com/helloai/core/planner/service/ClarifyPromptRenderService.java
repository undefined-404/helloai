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

    /**
     * 带附件可见性 + **目标附件集**的 CHAT 渲染（`ATT-3`）。
     *
     * <p>{@code attachmentTargets} 是联合决策判定的「本轮问的是哪几份附件」（1-based 清单序号）。
     * 第三层检索按此收窄：会话里两份文件、用户只问第二份时，只检索第二份的分片——
     * 否则第一份的内容会混进回答并占用注入预算。空/null 表示不限（会话级回退）。</p>
     */
    String renderChatPrompt(Long conversationId, String webSearchContext, boolean includeAttachment,
                            java.util.List<Integer> attachmentTargets);

    /**
     * 完整入口：附件可见性 + 显式勾选 + 模型判定（`ATT-3`）。
     *
     * <p>{@code selectedIds}（前端勾选）优先于 {@code attachmentTargets}（模型判定）——
     * 用户点了就是点了，模型不该再猜。</p>
     *
     * @param selectedIds 前端勾选的附件 id；null/空 = 未指定
     */
    String renderChatPrompt(Long conversationId, String webSearchContext, boolean includeAttachment,
                            java.util.List<Integer> attachmentTargets, java.util.List<Long> selectedIds);

    /** 渲染终稿直出 Prompt（/task 直达拆解模板）。 */
    String renderFinalizePrompt(Long conversationId, String webSearchContext);

    /**
     * 渲染结果 + 本轮要送模型的图片引用（`VU-8`）。
     *
     * <p><b>为什么必须与 Prompt 一起产出，而不是让调用方另查一次</b>：媒体引用的作用域
     * （{@code selectedIds} 优先、回落 {@code attachmentTargets}、再回落全量）与「哪几份附件的
     * 正文被注入」是<b>同一份判定</b>。分两次算会得到两个可能不一致的作用域——清单说「本轮只看
     * 第 2 份」，实际却把第 1 份的图送出去，用户完全无从察觉（判据 4：同一事实双写必分叉）。</p>
     *
     * <p><b>与 {@code renderChatPrompt} 三兄弟的关系</b>：那几个返回 {@code String}，行为逐字不变
     * （老调用方零改动）；本组并列返回带媒体的重载，避免把返回类型改掉而波及全部既有调用点。</p>
     *
     * @param prompt    渲染后的完整 Prompt 文本
     * @param mediaRefs 本轮要送模型的图片引用（绝不为 {@code null}；无图时为空表）
     */
    record RenderedPrompt(String prompt, java.util.List<com.helloai.core.agent.port.MediaRef> mediaRefs) {
    }

    /**
     * CHAT 模板 + 图片引用（`VU-8`）：参数与 {@link #renderChatPrompt(Long, String, boolean,
     * java.util.List, java.util.List)} 完全一致。
     *
     * <p>{@code includeAttachment=false}（联合决策判定本轮不涉及附件）时<b>不送图</b>：
     * 正文都刻意不注入，却单独把图送到模型，等于用另一种通道推翻了同一个判定。</p>
     */
    RenderedPrompt renderChatPromptWithMedia(Long conversationId, String webSearchContext,
                                             boolean includeAttachment,
                                             java.util.List<Integer> attachmentTargets,
                                             java.util.List<Long> selectedIds);

    /** CLARIFY 模板 + 图片引用（`VU-8`）。
     *
     * <p><b>`G-044`：为什么必须带 {@code selectedIds}</b>——澄清轮与终稿轮此前把勾选集
     * 硬编码为 {@code null}，导致用户转入方案模式后每一轮的附件勾选完全失效（只有 CHAT 模式生效）。
     * 作用域判定仍走同一份 {@code resolveScope}（勾选 &gt; 模型序号 &gt; 全量），
     * 不为澄清链单独发明一套。</p>
     *
     * @param selectedIds 本轮用户勾选的附件 id；{@code null}=未指定（走回落），{@code []}=明确一个都不要
     */
    RenderedPrompt renderClarifyPromptWithMedia(Long conversationId, String webSearchContext,
                                                java.util.List<Long> selectedIds);

    /** FINALIZE 模板 + 图片引用（`VU-8`）；{@code selectedIds} 语义同 {@link #renderClarifyPromptWithMedia}。 */
    RenderedPrompt renderFinalizePromptWithMedia(Long conversationId, String webSearchContext,
                                                 java.util.List<Long> selectedIds);

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

    /**
     * 同 {@link #applyDescriptionSectionGuard(RequirementConversation, AgentProfileSnapshot, String,
     * String, ClarifyReply)}，重试那一轮也带上本轮图片（`VU-8`）。
     *
     * <p><b>为什么重试必须带同样的图</b>：重试的产物会<b>覆盖首轮结果</b>。若首轮看图写了、
     * 重试那次看不到图，最终落库的终稿就丢了图里的语义，而用户完全不知道中间发生过一次重试
     * ——这是「静默丢图」里最难排查的一种（症状是「第一次生成对、重来一次就不对了」）。
     * 保持两次调用的媒体一致，重试才是同一个问题的第二次尝试，而不是换了个问题。</p>
     *
     * @param mediaRefs 本轮图片引用；{@code null} / 空 = 无图（与旧签名行为一致）
     */
    ClarifyReply applyDescriptionSectionGuard(RequirementConversation conversation,
                                              AgentProfileSnapshot planner, String prompt,
                                              String scene, ClarifyReply firstReply,
                                              java.util.List<com.helloai.core.agent.port.MediaRef> mediaRefs);
}
