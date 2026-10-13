package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

/**
 * 循环终止前上下文（REF-8.4 ② 收尾轮）。
 *
 * <p>{@link LoopHook#beforeTerminate} 的可写观察面：携带当前消息历史与草稿结果，使挂点在
 * 需要时能<b>有界重入一次模型</b>（追加收尾指令 → 重跑 → 返回修订结果）。默认 no-op（返回
 * null）⇒ 行为与改造前逐字一致。</p>
 *
 * @param chatModel   底层模型（收尾轮重跑用；不可空）
 * @param messages    当前会话消息历史（调用方持有的<b>可变</b>列表，挂点可追加收尾指令）
 * @param internalLlm 是否内部 LLM 执行链（{@code accessType == API_KEY_LLM}；收尾轮 internal-only 门控）
 * @param draft       自然 STOP 的草稿结果（挂点据此判定是否触发收尾轮）
 * @param maxIterations 本轮最大 LLM 调用轮数（收尾轮计 iterations 时与之取 min，防超配置上限）
 */
public record AgentLoopTermination(ChatModel chatModel, List<Message> messages,
                                   boolean internalLlm, AgentLoopResult draft, int maxIterations) {
}