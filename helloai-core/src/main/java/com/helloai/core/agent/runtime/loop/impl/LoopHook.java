package com.helloai.core.agent.runtime.loop.impl;

import com.helloai.core.agent.runtime.loop.AgentLoopResult;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.List;

/**
 * 循环三档具名挂点（REF-8.4）——对齐 DSH 三个可拦截点（{@code agent/pre-step} /
 * {@code agent/request-error} / {@code agent/turn-stopping}）的「挂点位置」，不强套 Spring AI Advisors。
 *
 * <p><b>借的是位置语义，不是框架容器</b>（plan §10.7.3 三处硬约束）：DSH 的
 * {@code agent/pre-step} 是「副作用后放行」的可重入 waterfall，Advisor 是线性装饰器表达不出；
 * {@code agent/turn-stopping} 是 {@code serial} 而非 waterfall。故载体 = 循环自有 hook 列表。</p>
 *
 * <p><b>默认全 no-op</b>：构造时未注册任何 hook（或注册 {@link CompactionHook} 之外的空列表）
 * ⇒ 行为与改造前逐字一致（同 REF-8.2.3 的 no-op 构造验收手法）。</p>
 *
 * <p>切片 1 只接线三档挂点骨架；{@link #beforeTerminate} 现为 no-op（接口契约在），
 * 其首条实现（轮报合格性 + 按需收尾轮）随 {@code VerificationFence} 切片刻意后接。</p>
 */
public interface LoopHook {

    /**
     * 每轮模型调用前的压力检查/准入（DSH {@code agent/pre-step} 等价物）。
     * 返回压缩结果的事件载荷；未触发/未改动返回 {@code null}。
     *
     * @param summarizationBudget 本执行内剩余可摘要次数
     */
    default CompactionOutcome beforeRequest(List<Message> messages, ChatModel chatModel,
                                            ChatOptions options, int summarizationBudget) {
        return null;
    }

    /**
     * 提供方调用抛错后的溢出恢复（DSH {@code agent/request-error} 等价物）。
     * 返回 {@code null} 或 {@code changed()==false} 时调用方判定「不可压缩」而原样抛错。
     */
    default CompactionOutcome onRequestError(List<Message> messages, ChatModel chatModel,
                                             ChatOptions options, int summarizationBudget) {
        return null;
    }

    /**
     * 终止前终态检查点（DSH {@code agent/turn-stopping} serial 等价物）。
     *
     * <p>切片 2 起升级为可改写结果：携带 {@link AgentLoopTermination}（消息历史 + 草稿结果），
     * 返回非 null 时调用方以其替换最终结果。默认返回 null（不改写）⇒ 行为与改造前逐字一致。</p>
     *
     * <p>首条实现 = {@link FinalReportFenceHook}（内部 LLM 收尾轮：缺/不合格 VERIFICATION 围栏时
     * 无工具重入一次模型自纠），write-only 纪律——异常时调用方吞掉并沿用草稿，不阻断。</p>
     *
     * @param termination 终止前上下文（消息历史 + 草稿结果 + 是否内部 LLM）
     * @return 修订后的最终结果；{@code null} = 不改写（沿用草稿）
     */
    default AgentLoopResult beforeTerminate(AgentLoopTermination termination) {
        // 默认空操作（不改写结果）
        return null;
    }
}