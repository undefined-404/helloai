package com.helloai.core.planner.service;

import com.helloai.core.planner.clarify.ChatRoundDecisionParser;
import com.helloai.core.planner.entity.RequirementConversation;
import com.helloai.core.planner.search.WebSearchOutcome;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyConversationDetail;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyReply;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifySelection;

import java.util.List;

/**
 * 澄清会话的<b>轮次编排主链</b>接口（RM9-c 从 {@link RequirementClarifyService} 实现类拆出的职责契约）：
 * 读状态 → 决策 → LLM → 落库。
 *
 * <p>实现见 {@code com.helloai.core.planner.service.impl.ClarifyRoundOrchestratorServiceImpl}。
 * 承接 {@code doRound} / {@code runRoundCore} 重载对（<b>整组迁移</b>）、联合决策、主回复落库半段，
 * 以及模式切换进入澄清轮的 {@code enterClarify}（原门面 {@code switchToClarify} 核心，下移以破除
 * 门面 ↔ 编排器的相互调用环，详见 {@code RM计划 §4.3.3}「与旧表的实现层微调」）。</p>
 */
public interface ClarifyRoundOrchestratorService {

    /** 本轮联合决策结果：决策本体 + 降级标志（降级=非 LLM 决策，AI 仍可观测）。 */
    record RoundDecision(ChatRoundDecisionParser.ChatRoundDecision decision, boolean degraded) {

        /** 降级默认决策（intent=chat、不搜索），绝不阻塞主流程。 */
        public static RoundDecision degradedDefaults() {
            return new RoundDecision(ChatRoundDecisionParser.defaults(), true);
        }
    }

    /** 向会话追加一条用户消息并走一轮 LLM（纯文本，无选项快照）。 */
    ClarifyConversationDetail sendMessage(Long conversationId, String message);

    /**
     * <b>只新建会话</b>（不跑 LLM 轮次）——同步 {@code create} 与流式 {@code streamCreate} 共用。
     *
     * <p>拆分原因（2026-10-18）：{@code create} 原实现是「建会话 + 同步跑首轮」打包在一起，
     * 导致<b>新会话首条消息只能走同步</b>（用户要等搜索 + 完整 LLM 才见到回复，无打字机、
     * 无思考过程）。拆出本方法后，流式入口可以先建会话、拿 id 首发 {@code session} 事件，
     * 再走与老会话完全相同的流式首轮。</p>
     *
     * <p>建会话口径保持原样：标题取消息前 50 字（{@code /task} 命令取附加文本）、
     * 状态 ACTIVE、轮数 0、模式恒为 CHAT、联网开关原样落库（null 兼容老数据语义）。</p>
     */
    RequirementConversation createConversation(String firstMessage, Long plannerAgentId,
                                               Boolean webSearchEnabled);

    /**
     * 同上，并<b>认领</b>新会话首句前上传的附件（{@code clientToken} 名下、{@code conversation_id}
     * 为空的临时批次）——认领后附件才随会话进入 {@code {{ATTACHMENT_MANIFEST}}}。
     *
     * <p>为什么认领放在建会里而不是另开一个端点：附件归属与会话创建是同一件事的两个半步，
     * 分开调会出现「会话建了、文件没挂上」的中间态，用户看到文件消失。放在同一次调用里，
     * 建会成功即归属确定。</p>
     *
     * @param clientToken 前端生成的新会话临时标识（可空=没传过附件）
     */
    RequirementConversation createConversation(String firstMessage, Long plannerAgentId,
                                               Boolean webSearchEnabled, String clientToken);
    /** 向会话追加一条用户消息并走一轮 LLM（可携带结构化选项回答快照）。 */
    ClarifyConversationDetail sendMessage(Long conversationId, String message, List<ClarifySelection> selections);

    /** 重试上一轮 LLM（仅当最后一条消息是 user；不新增 user 消息、不加轮数）。 */
    ClarifyConversationDetail retryRound(Long conversationId);

    /**
     * 编辑已发送的用户消息并重跑（2026-10-19）：物理删除目标消息<b>之后</b>的所有消息
     * （旧回复与后续追问）→ 更新该消息 content → 重跑该消息（同 {@link #retryRound}，
     * 不新增 user 消息、不加轮数）。仅支持「最后一条 user 消息」，更早消息抛 BizException。
     */
    ClarifyConversationDetail editMessage(Long conversationId, Long messageId, String newContent);

    /** 一轮澄清：存 user 消息 + round_count+1 → LLM 一轮（选人/渲染/解析/落库）。 */
    ClarifyConversationDetail doRound(RequirementConversation conversation, String userMessage);

    /** 一轮澄清（含可选的选择快照 payload）。 */
    ClarifyConversationDetail doRound(RequirementConversation conversation, String userMessage, String userPayload);

    /**
     * 切换到方案澄清模式并跑一轮（原门面 {@code switchToClarify} 核心）：先落库附加文本
     * （进 LLM 上下文），再切 CLARIFY 并跑一轮澄清（首轮强制 structured → 推荐卡片必出）。
     *
     * @param extraMessage 斜杠命令后的附加文本；空/空白则不加消息
     */
    ClarifyConversationDetail enterClarify(Long conversationId, String extraMessage);

    /** CHAT 轮联合决策调用：低预算 executeSync 输出 intent + 澄清问题 + 搜索决策；异常/校验失败降级。 */
    RoundDecision makeRoundDecision(RequirementConversation conversation, String userMessage);

    /** 应用 clarify 决策：落库单条纯确认意图卡并置待确认标记（不生成主回复）。 */
    ClarifyConversationDetail applyClarifyDecision(RequirementConversation conversation,
                                                   ChatRoundDecisionParser.ChatRoundDecision decision);

    /** CLARIFY 模式主回复落库（final 终稿 / question 追问卡），同步与流式共用。 */
    void persistClarifyOutcome(RequirementConversation conversation, ClarifyReply reply,
                               WebSearchOutcome webSearchOutcome);

    /**
     * CLARIFY 模式主回复落库（含过程数据）：{@code process} 落 payload 的 {@code process} 键，
     * 供前端在 done 后/刷新后回看思考与检索步骤（空过程不写该键）。
     */
    void persistClarifyOutcome(RequirementConversation conversation, ClarifyReply reply,
                               WebSearchOutcome webSearchOutcome, RoundProcess process);

    /** CHAT 模式主回复落库（结构化追问卡 / 纯文本），同步与流式共用。 */
    void persistChatOutcome(RequirementConversation conversation, String output,
                            WebSearchOutcome webSearchOutcome);

    /**
     * CHAT 模式主回复落库（含过程数据）：与 {@link #persistChatOutcome(RequirementConversation, String, WebSearchOutcome)}
     * 同构，额外把思考与检索步骤落 payload（空过程不写该键）。
     */
    void persistChatOutcome(RequirementConversation conversation, String output,
                            WebSearchOutcome webSearchOutcome, RoundProcess process);

    /**
     * 确认卡待确认状态的前置处理（同步 {@code sendMessage} 与流式 {@code streamRound} <b>共用</b>）。
     *
     * <p>语义（与原同步路径逐字一致）：确认卡点「确认」（selections 快照命中接受项）⇒ 切 CLARIFY
     * 模式并清标记；其它消息 ⇒ 仅清标记、继续自由对话。两处调用同一方法而非各写一遍——
     * 「同一事实双写必分叉」，此前流式路径直接抛「存在待确认卡片」，与同步路径行为不一致。</p>
     *
     * @return true = 本次确认了转方案（已切 CLARIFY）；false = 无待确认卡，或用户未选择接受
     */
    boolean resolvePendingClarifyConfirm(RequirementConversation conversation,
                                         List<ClarifySelection> selections);
}
