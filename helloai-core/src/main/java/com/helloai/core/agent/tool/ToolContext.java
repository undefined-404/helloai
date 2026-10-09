package com.helloai.core.agent.tool;

import com.helloai.common.constant.AgentAccessType;

import java.util.List;

/**
 * 工具解析上下文（REF-1.3）——「按条件可用」与「按上下文动态描述」两个语义位的输入事实。
 *
 * <p><b>命名说明</b>：与 spring-ai {@code org.springframework.ai.chat.model.ToolContext}
 * （工具<b>执行</b>期上下文，final class + Map）同名不同包、不同阶段——本类是<b>解析</b>期上下文，
 * 是 {@link com.helloai.core.agent.runtime.sandbox.SandboxContext}（{@code SandboxProvider.resolve(...)}）
 * 在工具面的同构物。二者仅在 {@code com.helloai.core.agent.tool.impl.DescribedToolCallback}
 * 中擦肩：该类是全库唯一同时见两者的文件（spring-ai 那个的 import 收敛于此）。</p>
 *
 * <p><b>字段纪律</b>：只放调用点手头已有的事实，不臆造、不塞大对象（不出现 ChatModel / prompt /
 * 实体 / 事件记录器）。新增字段必须同时给出消费者，否则不加。</p>
 *
 * <p><b>可空约定</b>：每个字段均可空；无上下文（{@link #empty()}）时，<b>仅依赖上下文的条件
 * 必须 fail-open</b>（判为可用）——不知道 ≠ 不具备。见
 * {@link ToolCallbackContributor#toolAvailability()} 的契约。</p>
 *
 * @param agentId        Agent ID（消费方：REF-4 检索工具按 Agent 归属 / KB 授权判定）
 * @param taskId         主任务 ID（消费方：按任务阶段 / 能力开关决定工具可见性；动态描述注入任务语境）
 * @param subTaskId      子任务 ID（消费方：REF-4 KB 范围判定；描述反映本子任务是否已有前置产出）
 * @param turn           Turn 序号（动态描述的主输入：本轮进度 / 剩余预算）
 * @param accessType     接入类型（消费方：按接入类型调整描述，如 API_KEY_LLM 下提示无 MCP 会话）
 * @param requiredSkills 命中技能（消费方：命中技能对描述的增强，如技能已声明联网能力 ⇒ 点名校验规则）
 */
public record ToolContext(
        Long agentId,
        Long taskId,
        Long subTaskId,
        int turn,
        AgentAccessType accessType,
        List<String> requiredSkills) {

    public ToolContext {
        requiredSkills = requiredSkills == null ? List.of() : List.copyOf(requiredSkills);
    }

    /**
     * 空上下文（不含任何事实）。<b>仅测试与防御路径使用</b>——生产调用点
     * （{@code RuntimeTurnExecutor}）必须装配真实事实，不得用它代替。
     */
    public static ToolContext empty() {
        return new ToolContext(null, null, null, 0, null, List.of());
    }
}
