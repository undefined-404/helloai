package com.helloai.core.agent.runtime;

import com.helloai.common.constant.ExecutionStatus;
import com.helloai.core.agent.output.ArtifactFile;
import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.List;

/**
 * Agent 运行时执行结果契约（Phase 0 C1）。
 *
 * <p>一次 {@link AgentRuntime#execute} 的完整生命周期结果：终态 + 输出 + 事件类型轨迹 + 待物化交付物。</p>
 *
 * <p>与旧执行链 {@code agent.domain.AgentResult}（LLM/Executor 层级返回值）区分：
 * 本类型是 Runtime 契约层结果，C3 下线旧 Executor 后前者随之退役。</p>
 *
 * @see AgentRuntime
 */
@Value
@Builder
public class AgentExecutionResult {

    /** 执行终态（SUCCESS / FAILED / TIMEOUT；PENDING / RUNNING 为过程态，不应出现在结果中）。 */
    ExecutionStatus status;

    /** 执行输出正文（可为 null：失败 / 超时场景）。 */
    String output;

    /** 推理模型思考过程（可为 null；正文与思考分离后保留，供对话流 / 诊断展示）。 */
    String thinking;

    /** 结束原因（"STOP" / "MAX_ITERATIONS" / "ERROR"；可为 null：契约化失败路径）。 */
    String finishReason;

    /** Token 用量（可为 null：当前 AgentLoop 未统计，预留 B3 tokens 统计挂载点）。 */
    Integer tokenUsage;

    /** 本次 execute 按发送顺序发出的事件类型列表（snake_case，与 agent_event.event_type 同构；供对账 / 诊断）。 */
    @Builder.Default
    List<String> eventTypes = Collections.emptyList();

    /** 本次执行待物化交付物（文本产物；空列表 = 无交付物）。 */
    @Builder.Default
    List<ArtifactFile> artifacts = Collections.emptyList();
}