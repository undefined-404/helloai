package com.helloai.core.planner.assistant.service;

import com.helloai.core.planner.service.RequirementClarifyService.ChatStreamEvent;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifyConversationDetail;
import reactor.core.publisher.Flux;

/**
 * 个人助理对话服务（PA-11）。
 *
 * <p>与需求澄清的区别：助理对话是「检索历史 + 通用问答」，<b>不产出任务需求草案</b>；
 * 需要建任务时由 {@link #createTaskFromContext} 显式创建一条需求会话（带血缘），
 * 由用户在新会话里继续澄清。</p>
 *
 * <p>会话与需求会话<b>同构存储</b>（{@code requirement_conversation.kind=ASSISTANT}），
 * 消息仍落 {@code requirement_message}（真相源）。</p>
 */
public interface AssistantChatService {

    /**
     * 新建助理会话并跑首轮（建会话 + 一轮问答）。
     *
     * @param firstMessage   首条用户消息
     * @param plannerAgentId 指定助理使用的 Planner Agent（空=自动选择）
     * @param input          本轮附加入参（联网搜索开关 / 技能 / 附件 / 新会话附件临时归属）
     */
    ClarifyConversationDetail createConversation(String firstMessage, Long plannerAgentId,
                                                 AssistantRoundInput input);

    /**
     * 流式新建助理会话并跑首轮（SSE）：首帧 {@code session}（data=会话 id），
     * 随后 {@code progress}/{@code reasoning}/{@code token} 增量，终帧 {@code done}/{@code error}。
     *
     * <p>与同步 {@link #createConversation} 同语义：检索增强预注入、联网搜索/技能/附件、
     * 幂等落 user 消息；差别只在主回复走 {@code executeStream}，且落库附带
     * {@code webSearch}/{@code process} 过程 payload。</p>
     */
    Flux<ChatStreamEvent> streamCreateConversation(String firstMessage, Long plannerAgentId,
                                                   AssistantRoundInput input);

    /**
     * 在助理会话里流式追加一条消息并跑一轮（SSE），事件协议同 {@link #streamCreateConversation}。
     *
     * <p>同步前置段（落 user 消息 / 自动压缩）持会话锁串行化，解锁后再跑检索与 LLM 流，
     * 保证「停止生成」时不会占着会话锁。</p>
     */
    Flux<ChatStreamEvent> streamSendMessage(Long conversationId, String message, AssistantRoundInput input);

    /**
     * 在助理会话里追加一条消息并跑一轮问答。
     *
     * @param input 本轮附加入参（联网搜索开关 / 技能 / 附件）
     */
    ClarifyConversationDetail sendMessage(Long conversationId, String message, AssistantRoundInput input);

    /**
     * 从助理会话显式创建一条需求会话（血缘：来源会话 + 可选来源消息），
     * 把近期对话摘要作为需求会话的首条用户消息，随后由需求澄清流程接管。
     *
     * @return 新建的需求会话 id
     */
    Long createTaskFromContext(Long conversationId, Long forkedFromMessageId);
}