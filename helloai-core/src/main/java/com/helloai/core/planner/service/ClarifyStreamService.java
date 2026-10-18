package com.helloai.core.planner.service;

import com.helloai.core.planner.service.RequirementClarifyService.ChatStreamEvent;
import com.helloai.core.planner.service.RequirementClarifyService.ClarifySelection;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 澄清会话的 <b>SSE 流式面</b>接口（RM9-c 从 {@link RequirementClarifyService} 实现类拆出的职责契约，
 * 与同步链零耦合）。
 *
 * <p>实现见 {@code com.helloai.core.planner.service.impl.ClarifyStreamServiceImpl}。
 * 事件协议：token（主回复增量块）→ done（落库完成，[DONE]）/ error（任意失败，用户消息可能
 * 已落库，可走 {@link RequirementClarifyService#retryRound(Long)} 重试）。</p>
 */
public interface ClarifyStreamService {

    /**
     * 流式对话轮（Chat SSE）：与同步 sendMessage 同语义的前置（校验 / 落 user 消息并 round+1 /
     * 意图决策 / 三态搜索），仅主回复 LLM 调用走 token 增量流；任何异常统一转 {@code error} 事件。
     *
     * @return token 增量事件流，恒以 {@link ChatStreamEvent.Type#DONE} 或
     *         {@link ChatStreamEvent.Type#ERROR} 收尾
     */
    Flux<ChatStreamEvent> streamRound(Long conversationId, String message, List<ClarifySelection> selections);

    /**
     * 流式新建会话并跑首轮（Chat SSE）：<b>新会话首条消息</b>的流式入口。
     *
     * <p><b>为什么需要</b>（2026-10-18 实测）：前端新会话走同步 {@code create}——该接口要跑完
     * 「意图决策 + 联网搜索（实测 21.5s）+ 完整 LLM 生成」才一次性返回，用户全程只看到
     * 「思考中」占位，既无打字机也无思考过程。本接口把「建会话」与「跑首轮」拆开后，
     * 新会话与老会话走同一条流式通道。</p>
     *
     * <p>事件序：首帧必为 {@link ChatStreamEvent.Type#SESSION}（data=会话 id，前端据此在
     * done 后拉 detail 收敛），随后与 {@link #streamRound} 完全一致。</p>
     *
     * @return session + token/reasoning 增量事件流，恒以 DONE 或 ERROR 收尾
     */
    Flux<ChatStreamEvent> streamCreate(String message, Long plannerAgentId, Boolean webSearchEnabled);
}
